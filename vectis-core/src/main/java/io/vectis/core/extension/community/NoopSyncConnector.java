package io.vectis.core.extension.community;

import io.vectis.extension.spi.SyncConnector;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Native community {@link SyncConnector}: a disabled no-op.
 *
 * <p>Always-present, lowest-priority floor (see {@link CommunityAuditLogger}
 * for why this is a {@code @Priority(0)} bean rather than a
 * {@code @DefaultBean}). It reports {@link #available()} {@code false}, which
 * is how the core expresses "external sync is simply off in this deployment"
 * without any missing-bean fault.</p>
 */
@ApplicationScoped
@Priority(0)
public class NoopSyncConnector implements SyncConnector {

    @Override
    public String id() {
        return "community-none";
    }

    @Override
    public boolean available() {
        return false;
    }
}
