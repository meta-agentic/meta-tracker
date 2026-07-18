package io.vectis.extension.spi;

/**
 * Extension point: sinks an {@link AuditEvent} somewhere durable.
 *
 * <p>The OSS core ships a community implementation (local, best-effort). A
 * commercial edition may supply a tamper-evident / externally-shipped
 * implementation packaged in a separate, separately-licensed JAR. The core
 * discovers whichever implementation is on the deployment classpath at
 * runtime; if none is, the community default is used and the core still
 * boots.</p>
 *
 * <p>Implementations are expected to be non-blocking or fast enough to run on
 * the audit path; heavy work should be handed off asynchronously by the
 * implementation itself.</p>
 */
public interface AuditLogger {

    /** Record a single audit event. Must not throw on the caller's path. */
    void record(AuditEvent event);

    /**
     * A short, stable identifier for this implementation, surfaced in
     * diagnostics so operators can confirm which plugin is active
     * (e.g. {@code "community-local"}, {@code "enterprise-tamper-evident"}).
     */
    String id();
}
