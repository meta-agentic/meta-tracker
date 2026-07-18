package io.vectis.extension.spi;

/**
 * Extension point: a bidirectional bridge between a Vectis workspace and an
 * external tracker (Jira, GitHub Projects, a customer's in-house system).
 *
 * <p>The community core ships no live connector — only a no-op fallback — so
 * that a plain OSS deployment boots cleanly with sync simply disabled.
 * Commercial or third-party connectors are discovered at runtime the same way
 * {@link AuditLogger} implementations are.</p>
 */
public interface SyncConnector {

    /** Stable identifier for the target system, e.g. {@code "jira-cloud"}. */
    String id();

    /**
     * Whether this connector is currently able to exchange work. The no-op
     * community fallback returns {@code false}; a configured commercial
     * connector returns {@code true} once its credentials resolve.
     */
    boolean available();
}
