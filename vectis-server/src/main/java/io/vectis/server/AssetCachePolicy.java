package io.vectis.server;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Cache policy for the web client's content-hashed {@code /assets} (VEC-78).
 *
 * <p>A found asset is immutable for a year: a changed file is a new URL. A miss
 * must not be. During a rolling update a shell from a new pod can ask an old pod
 * for a hash it does not have yet; a 404 cached as immutable would pin that miss
 * in the browser or a shared cache for a year. So the policy is decided on the
 * status when the headers are written, which a static header filter cannot do.
 */
@ApplicationScoped
public class AssetCachePolicy {

    static final String IMMUTABLE = "public, max-age=31536000, immutable";
    static final String MISS = "no-store";

    private static final int PRIORITY = 100;

    void register(@Observes Filters filters) {
        filters.register(AssetCachePolicy::handle, PRIORITY);
    }

    static void handle(RoutingContext ctx) {
        if (ctx.normalizedPath().startsWith("/assets/")) {
            ctx.addHeadersEndHandler(ignored -> ctx.response().headers()
                .set(HttpHeaders.CACHE_CONTROL, forStatus(ctx.response().getStatusCode())));
        }
        ctx.next();
    }

    /** 304 refreshes a stored copy of a found asset, so it keeps the found policy. */
    static String forStatus(int status) {
        return (status >= 200 && status < 300) || status == 304 ? IMMUTABLE : MISS;
    }
}
