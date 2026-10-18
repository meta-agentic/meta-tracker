package io.vectis.server;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * History fallback for the single-page client served from this same origin
 * (VEC-78). The built client sits in {@code META-INF/resources}, so Quarkus
 * serves {@code /index.html} and the hashed {@code /assets/*} itself; this
 * filter only makes a client-side route such as {@code /board/VEC} reload into
 * the shell instead of a 404.
 *
 * <p>It never falls back for the API or the Quarkus endpoints: {@code /api/unknown}
 * must stay a 404 a client can read, not a 200 HTML page it would try to parse.
 * A path whose last segment has an extension is a file, so a missing asset is a
 * 404 too. Only GET and HEAD are rerouted; a write to a client route is not a
 * navigation.
 */
@ApplicationScoped
public class SpaHistoryFallback {

    static final String SHELL = "/index.html";

    /** Ahead of the routes, so the reroute happens before static resources answer 404. */
    private static final int PRIORITY = 100;

    void register(@Observes Filters filters) {
        filters.register(SpaHistoryFallback::handle, PRIORITY);
    }

    static void handle(RoutingContext ctx) {
        if (isClientRoute(ctx.request().method(), ctx.normalizedPath())) {
            ctx.reroute(SHELL);
        } else {
            ctx.next();
        }
    }

    static boolean isClientRoute(HttpMethod method, String path) {
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return false;
        }
        if (isUnder(path, "/api") || isUnder(path, "/q")) {
            return false;
        }
        return !hasExtension(path);
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean hasExtension(String path) {
        return path.lastIndexOf('.') > path.lastIndexOf('/');
    }
}
