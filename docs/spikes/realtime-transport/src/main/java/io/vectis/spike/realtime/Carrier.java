package io.vectis.spike.realtime;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.pubsub.PgSubscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * How a committed event leaves this instance, per {@code spike.carrier}.
 *
 * <ul>
 *   <li>{@code pg}: inside the write transaction, {@code pg_notify} a ~40-byte doorbell
 *       {@code <workspaceId>:<seq>}. PostgreSQL delivers it to every listening session
 *       only if the transaction commits, and in commit order. Each instance holds one
 *       dedicated LISTEN connection (a {@link PgSubscriber}, already in vertx-pg-client)
 *       and reads the event bodies from {@code workspace_event}.
 *   <li>{@code none}: after commit, hand the event to this instance's own streams. The
 *       naive in-JVM broadcast; other instances never hear of it.
 *   <li>{@code pg-postcommit}: after commit (optionally after a stall), publish the full
 *       event on a second channel. This is the shape of any broker publish that is not
 *       part of the database transaction, and exists to demonstrate why that is unsafe.
 * </ul>
 */
@ApplicationScoped
public class Carrier {

    static final String DOORBELL = "vectis_workspace";
    static final String INLINE = "vectis_workspace_inline";

    private final Pool pool;
    private final Hub hub;
    private final Vertx vertx;
    private final String carrier;
    private final String instance;
    private final long publishDelayMs;
    private final String url;
    private final String user;
    private final String password;
    private PgSubscriber subscriber;
    private int listens;

    public Carrier(Pool pool, Hub hub, Vertx vertx,
            @ConfigProperty(name = "spike.carrier") String carrier,
            @ConfigProperty(name = "spike.instance") String instance,
            @ConfigProperty(name = "spike.publish-delay-ms") long publishDelayMs,
            @ConfigProperty(name = "quarkus.datasource.reactive.url") String url,
            @ConfigProperty(name = "quarkus.datasource.username") String user,
            @ConfigProperty(name = "quarkus.datasource.password") String password) {
        if (!carrier.equals("pg") && !carrier.equals("none") && !carrier.equals("pg-postcommit")) {
            throw new IllegalArgumentException("spike.carrier must be pg, none or pg-postcommit: " + carrier);
        }
        this.pool = pool;
        this.hub = hub;
        this.vertx = vertx;
        this.carrier = carrier;
        this.instance = instance;
        this.publishDelayMs = publishDelayMs;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    String carrier() {
        return carrier;
    }

    String instance() {
        return instance;
    }

    void onStart(@Observes StartupEvent event) {
        vertx.setPeriodic(20_000, id -> hub.keepAlive());
        if (carrier.equals("none")) {
            Log.infof("instance %s: carrier none (in-JVM broadcast only)", instance);
            return;
        }
        PgConnectOptions options = PgConnectOptions.fromUri(url).setUser(user).setPassword(password);
        subscriber = PgSubscriber.subscriber(vertx, options)
                // Reconnect forever, every 250 ms. The catch-up on re-subscribe makes the
                // outage cost latency only.
                .reconnectPolicy(retries -> 250L);
        subscriber.channel(DOORBELL)
                .subscribeHandler(v -> {
                    Log.infof("instance %s: LISTEN %s established (#%d)", instance, DOORBELL, ++listens);
                    hub.onListening();
                })
                .handler(this::onDoorbell);
        subscriber.channel(INLINE).handler(this::onInline);
        subscriber.connect().toCompletionStage().toCompletableFuture().join();
        Log.infof("instance %s: carrier %s", instance, carrier);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (subscriber != null) {
            subscriber.close();
        }
    }

    private void onDoorbell(String payload) {
        int colon = payload.indexOf(':');
        hub.onDoorbell(UUID.fromString(payload.substring(0, colon)), Long.parseLong(payload.substring(colon + 1)));
    }

    private void onInline(String payload) {
        JsonObject event = new JsonObject(payload);
        hub.deliverAsArrived(UUID.fromString(event.getString("workspaceId")), event.getLong("seq"),
                event.getString("type"), payload);
    }

    /** Runs inside the write transaction, after the event row is inserted. */
    Uni<Void> inTransaction(SqlConnection conn, UUID workspaceId, long seq) {
        if (!carrier.equals("pg")) {
            return Uni.createFrom().voidItem();
        }
        return conn.preparedQuery("select pg_notify($1, $2)")
                .execute(Tuple.of(DOORBELL, workspaceId + ":" + seq))
                .replaceWithVoid();
    }

    /** Runs after the write transaction committed. */
    Uni<Void> afterCommit(UUID workspaceId, long seq, String type, String json) {
        return switch (carrier) {
            case "none" -> {
                hub.deliverAsArrived(workspaceId, seq, type, json);
                yield Uni.createFrom().voidItem();
            }
            case "pg-postcommit" -> {
                Uni<Void> publish = pool.preparedQuery("select pg_notify($1, $2)")
                        .execute(Tuple.of(INLINE, json))
                        .replaceWithVoid()
                        .onFailure().invoke(f -> Log.errorf("instance %s: post-commit publish of seq %d failed: %s",
                                instance, seq, f.getMessage()));
                if (publishDelayMs > 0) {
                    // Fire and forget, like a producer with its own send buffer: the HTTP
                    // response does not wait for the publish.
                    Uni.createFrom().voidItem().onItem().delayIt().by(Duration.ofMillis(publishDelayMs))
                            .chain(() -> publish).subscribe().with(v -> {}, f -> {});
                    yield Uni.createFrom().voidItem();
                }
                yield publish.onFailure().recoverWithNull().replaceWithVoid();
            }
            default -> Uni.createFrom().voidItem();
        };
    }
}
