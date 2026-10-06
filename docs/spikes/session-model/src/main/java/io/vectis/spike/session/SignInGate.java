package io.vectis.spike.session;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Sends a caller with no session to Vectis's own sign-in page BEFORE the OIDC code flow starts.
 *
 * <p>Needed because the provider serves no login page: {@code /authorize} without a provider
 * session answers {@code 400 access_denied} instead of redirecting anywhere (finding F1). So the
 * code flow may only begin once the browser holds the provider's session cookie, which the
 * sign-in page obtains by posting the credentials to the provider's {@code /login}.
 *
 * <p>Runs ahead of the authentication handler. API and event-stream calls are never redirected:
 * they get {@code 401}, because neither {@code fetch} nor {@code EventSource} can follow a
 * redirect into an HTML login (D7).
 */
@Singleton
public class SignInGate {

    /** Ahead of Quarkus's authentication route (see the spike notes for the measured order). */
    static final int ORDER = Integer.MIN_VALUE + 100;

    private static final Set<String> PUBLIC = Set.of(
            "/signin.html", "/signin.js", "/app.css", "/auth/callback", "/auth/start", "/auth/signin-config");

    void install(@Observes Router router) {
        router.route().order(ORDER).handler(this::gate);
    }

    private void gate(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (PUBLIC.contains(path) || path.startsWith("/q/")
                || rc.request().getHeader("Authorization") != null
                || hasSessionCookie(rc)) {
            rc.next();
            return;
        }
        boolean navigation = rc.request().method() == HttpMethod.GET
                && !path.startsWith("/api/")
                && accepts(rc, "text/html");
        if (navigation) {
            String target = rc.request().uri();
            rc.response().setStatusCode(302)
                    .putHeader("Location", "/signin.html?next=" + URLEncoder.encode(target, StandardCharsets.UTF_8))
                    .putHeader("Cache-Control", "no-store")
                    .end();
        } else {
            rc.response().setStatusCode(401)
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Cache-Control", "no-store")
                    .end("{\"error\":\"unauthenticated\"}");
        }
    }

    private static boolean hasSessionCookie(RoutingContext rc) {
        // q_session, or its split parts (q_session_at / q_session_rt) — names are Quarkus's defaults.
        return rc.request().cookies().stream().anyMatch(c -> c.getName().startsWith("q_session"));
    }

    private static boolean accepts(RoutingContext rc, String type) {
        String accept = rc.request().getHeader("Accept");
        return accept != null && accept.contains(type);
    }
}
