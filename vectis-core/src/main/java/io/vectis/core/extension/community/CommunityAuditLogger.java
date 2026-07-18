package io.vectis.core.extension.community;

import io.vectis.extension.spi.AuditEvent;
import io.vectis.extension.spi.AuditLogger;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Native community {@link AuditLogger}: best-effort local logging.
 *
 * <p>This is the always-present, lowest-priority floor. It is a plain
 * {@code @Priority(0)} bean rather than a {@code @DefaultBean}: a
 * {@code @DefaultBean} is suppressed at build time by the mere <em>presence</em>
 * of a commercial audit bean, but that commercial bean may be gated OFF at
 * <em>runtime</em> (e.g. via {@code @LookupIfProperty} licensing). If the floor
 * were suppressed at build time and the commercial bean excluded at runtime,
 * the discovery lookup would be empty and the core would fault — the exact
 * failure this design avoids. Keeping the floor always registered but
 * lowest-priority means a licensed commercial plugin outranks it, while an
 * unlicensed-but-present plugin cleanly falls through to it.</p>
 */
@ApplicationScoped
@Priority(0)
public class CommunityAuditLogger implements AuditLogger {

    private static final Logger LOG = Logger.getLogger(CommunityAuditLogger.class);

    @Override
    public void record(AuditEvent event) {
        LOG.infof("audit tenant=%s actor=%s action=%s at=%s attrs=%s",
                event.tenantId(), event.actor(), event.action(), event.at(), event.attributes());
    }

    @Override
    public String id() {
        return "community-local";
    }
}
