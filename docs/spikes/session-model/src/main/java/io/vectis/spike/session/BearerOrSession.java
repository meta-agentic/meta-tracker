package io.vectis.spike.session;

import io.quarkus.oidc.TenantResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Picks the OIDC tenant per request: a caller presenting {@code Authorization: Bearer} is an
 * agent or tool and is validated as a resource-server request ({@code bearer}); everything
 * else is the browser and uses the code-flow session (the default tenant).
 */
@ApplicationScoped
public class BearerOrSession implements TenantResolver {

    @Override
    public String resolve(RoutingContext context) {
        String authorization = context.request().getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? "bearer"
                : null;
    }
}
