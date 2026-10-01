package io.vectis.spike.realtime;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The prototype's HTTP surface. Paths under {@code /api/v1} are shaped like the ones
 * VEC-17 would add; paths under {@code /spike} exist only to drive the demonstration.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class Api {

    private static final int MAX_BODY = 256 * 1024;

    private final Pool pool;
    private final Hub hub;
    private final Writes writes;
    private final Carrier carrier;

    public Api(Pool pool, Hub hub, Writes writes, Carrier carrier) {
        this.pool = pool;
        this.hub = hub;
        this.writes = writes;
        this.carrier = carrier;
    }

    // ---- the stream -------------------------------------------------------------------

    /**
     * One stream per workspace. The cursor is the {@code Last-Event-ID} header, which the
     * browser sends by itself on reconnect; {@code ?after=} is for the first connect after
     * a snapshot, since {@code EventSource} cannot set headers. The header wins.
     */
    @GET
    @Path("api/v1/workspaces/{ws}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void events(@PathParam("ws") String ws, @HeaderParam("Last-Event-ID") String lastEventId,
            @QueryParam("after") String after, @Context SseEventSink sink, @Context Sse sse) {
        Long cursor = cursor(lastEventId != null ? lastEventId : after);
        UUID workspaceId = pool.preparedQuery("select id from workspace where key = $1")
                .execute(Tuple.of(ws))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getUUID("id"))
                .await().atMost(Duration.ofSeconds(5));
        if (workspaceId == null) {
            throw new NotFoundException("no workspace " + ws);
        }
        hub.subscribe(workspaceId, ws, cursor, sink, sse);
    }

    private static Long cursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new BadRequestException("cursor must be >= 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BadRequestException("cursor must be an integer");
        }
    }

    // ---- reads ------------------------------------------------------------------------

    /** Items plus the seq they are current to, read in one statement and so one snapshot. */
    @GET
    @Path("api/v1/workspaces/{ws}/snapshot")
    public Uni<Response> snapshot(@PathParam("ws") String ws) {
        return pool.preparedQuery("""
                        select w.id as ws_id, w.event_seq, w.config_revision,
                               i.id, i.key, i.board_id, i.column_id, i.title, i.rank, i.fields,
                               i.sprint_id, i.version, i.updated_at
                          from workspace w
                          left join item i on i.workspace_id = w.id
                         where w.key = $1
                         order by i.key
                        """)
                .execute(Tuple.of(ws))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new NotFoundException("no workspace " + ws);
                    }
                    JsonArray items = new JsonArray();
                    Row first = null;
                    for (Row row : rows) {
                        first = first == null ? row : first;
                        if (row.getUUID("id") != null) {
                            items.add(Events.item(row));
                        }
                    }
                    JsonObject body = new JsonObject()
                            .put("workspaceId", first.getUUID("ws_id").toString())
                            .put("seq", first.getLong("event_seq"))
                            .put("configurationRevision", first.getLong("config_revision"))
                            .put("items", items);
                    return Response.ok(body.encode()).build();
                });
    }

    @GET
    @Path("spike/whoami")
    public String whoami() {
        return new JsonObject().put("instance", carrier.instance()).put("carrier", carrier.carrier())
                .put("streams", hub.streamCount()).encode();
    }

    // ---- writes -----------------------------------------------------------------------

    @POST
    @Path("api/v1/workspaces/{ws}/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> create(@PathParam("ws") String ws, @HeaderParam("Vectis-Origin") String originHeader, String raw) {
        JsonObject body = body(raw);
        String title = title(body, true);
        UUID boardId = uuid(body, "boardId");
        UUID columnId = uuid(body, "columnId");
        String rank = body.getString("rank", "n");
        JsonObject fields = body.getJsonObject("fields", new JsonObject());
        String origin = Events.origin(originHeader);
        return respond(writes.write(ws, true, l -> l.conn().preparedQuery("insert into item (id, workspace_id, board_id, "
                                + "column_id, key, title, rank, fields) values ($1, $2, $3, $4, $5, $6, $7, $8) returning "
                                + Events.ITEM_COLUMNS)
                .execute(Tuple.from(new Object[] {Ids.next(), l.workspaceId(), boardId, columnId,
                    l.workspaceKey() + "-" + l.itemNumber(), title, rank, fields}))
                .map(rows -> built("item.created", l, origin, rows.iterator().next(),
                        List.of("key", "boardId", "columnId", "title", "rank", "fields")))), 201);
    }

    @PATCH
    @Path("api/v1/workspaces/{ws}/items/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> edit(@PathParam("ws") String ws, @PathParam("key") String key,
            @HeaderParam("Vectis-Origin") String originHeader, String raw) {
        JsonObject body = body(raw);
        String title = title(body, false);
        JsonObject fields = body.getJsonObject("fields");
        if (title == null && fields == null) {
            throw new BadRequestException("nothing to change");
        }
        List<String> changed = new ArrayList<>();
        if (title != null) {
            changed.add("title");
        }
        if (fields != null) {
            fields.fieldNames().forEach(name -> changed.add("fields." + name));
        }
        String origin = Events.origin(originHeader);
        return respond(writes.write(ws, false, l -> l.conn().preparedQuery("""
                        update item
                           set title = coalesce($3, title),
                               fields = fields || coalesce($4, '{}'::jsonb),
                               version = version + 1, updated_at = now()
                         where workspace_id = $1 and key = $2
                     returning\s""" + Events.ITEM_COLUMNS)
                .execute(Tuple.of(l.workspaceId(), key, title, fields))
                .map(rows -> built("item.updated", l, origin, single(rows.iterator(), key), changed))), 200);
    }

    @POST
    @Path("api/v1/workspaces/{ws}/items/{key}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> move(@PathParam("ws") String ws, @PathParam("key") String key,
            @HeaderParam("Vectis-Origin") String originHeader, String raw) {
        JsonObject body = body(raw);
        UUID columnId = uuid(body, "columnId");
        String rank = body.getString("rank");
        if (rank == null || rank.isEmpty() || rank.length() > 64) {
            throw new BadRequestException("rank is required, at most 64 characters");
        }
        String origin = Events.origin(originHeader);
        return respond(writes.write(ws, false, l -> l.conn().preparedQuery("""
                        update item
                           set column_id = $3, rank = $4, version = version + 1, updated_at = now()
                         where workspace_id = $1 and key = $2
                     returning\s""" + Events.ITEM_COLUMNS)
                .execute(Tuple.of(l.workspaceId(), key, columnId, rank))
                .map(rows -> built("item.moved", l, origin, single(rows.iterator(), key), List.of("columnId", "rank")))), 200);
    }

    /** Stands in for VEC-45's delta PUT and upgrade: bumps the revision, emits the event. */
    @POST
    @Path("spike/workspaces/{ws}/configuration")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> configuration(@PathParam("ws") String ws, @HeaderParam("Vectis-Origin") String originHeader, String raw) {
        JsonObject cause = body(raw).getJsonObject("cause");
        String kind = cause == null ? null : cause.getString("kind");
        if (!"delta".equals(kind) && !"upgrade".equals(kind)) {
            throw new BadRequestException("cause.kind must be delta or upgrade");
        }
        return respond(writes.changeConfiguration(ws, Events.origin(originHeader), cause), 200);
    }

    /** Stands in for a template level publishing a version its descendants inherit. */
    @POST
    @Path("spike/templates/{template}/publish")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<String> publish(@PathParam("template") String template, @HeaderParam("Vectis-Origin") String originHeader, String raw) {
        int version = body(raw).getInteger("version", 1);
        return writes.publishAncestor(template, version, Events.origin(originHeader))
                .map(all -> new JsonObject().put("affected", new JsonArray(all.stream().map(w -> w.built().body()).toList())).encode());
    }

    @POST
    @Path("spike/reset")
    public Uni<String> reset() {
        return Seed.reset(pool);
    }

    // ---- helpers ----------------------------------------------------------------------

    private static Writes.Built built(String type, Writes.Locked l, String origin, Row row, List<String> changed) {
        JsonObject item = Events.item(row);
        return new Writes.Built(type, Events.itemEvent(type, l.workspaceId(), l.seq(), origin, item, changed), item);
    }

    private static Row single(java.util.Iterator<Row> rows, String key) {
        if (!rows.hasNext()) {
            throw new NotFoundException("no item " + key);
        }
        return rows.next();
    }

    private static Uni<Response> respond(Uni<Writes.Written> written, int status) {
        return written.map(w -> Response.status(status)
                .header("Vectis-Seq", w.seq())
                .entity(w.built().body().encode())
                .build());
    }

    private static JsonObject body(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        if (raw.length() > MAX_BODY) {
            throw new BadRequestException("body over " + MAX_BODY + " bytes");
        }
        try {
            return new JsonObject(raw);
        } catch (DecodeException | ClassCastException e) {
            throw new BadRequestException("body must be a JSON object");
        }
    }

    private static String title(JsonObject body, boolean required) {
        String title = body.getString("title");
        if (title == null && !required) {
            return null;
        }
        if (title == null || title.isBlank() || title.length() > 500) {
            throw new BadRequestException("title is required, at most 500 characters");
        }
        return title;
    }

    private static UUID uuid(JsonObject body, String name) {
        try {
            return UUID.fromString(body.getString(name));
        } catch (RuntimeException e) {
            throw new BadRequestException(name + " must be a uuid");
        }
    }
}
