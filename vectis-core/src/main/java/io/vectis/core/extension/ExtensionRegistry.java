package io.vectis.core.extension;

import io.vectis.extension.spi.AuditLogger;
import io.vectis.extension.spi.SyncConnector;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Runtime discovery of extension-point implementations.
 *
 * <p>This is the spike's core deliverable. It resolves plugins <em>at
 * runtime</em> from whatever is on the deployment classpath, using ArC's
 * programmatic CDI facilities:</p>
 *
 * <ul>
 *   <li>{@code @All List<T>} — every discovered implementation, already sorted
 *       by {@code @Priority} (highest first). Selection is "highest priority
 *       wins", so a commercial plugin declaring a higher priority supersedes
 *       the community default with no core code change.</li>
 *   <li>{@link Instance} — the programmatic lookup handle from the dev notes,
 *       used here only for resolvability diagnostics.</li>
 * </ul>
 *
 * <p>Because every extension point has an always-present, lowest-priority
 * ({@code @Priority(0)}) community floor, the {@code @All} list is never empty
 * and {@link #activeAuditLogger()} / {@link #activeSyncConnector()} never fault,
 * whether or not any commercial JAR is present. The floor is deliberately a
 * plain bean rather than a {@code @DefaultBean}: a {@code @DefaultBean} is
 * suppressed at build time by the mere presence of another bean of the type,
 * which — combined with a runtime {@code @LookupIfProperty} license gate — could
 * leave the lookup empty when a plugin is present but unlicensed. Discovery is
 * resolved once by the build-time ArC container (no runtime classpath scan), so
 * it adds no latency to the boot loop.</p>
 */
@ApplicationScoped
public class ExtensionRegistry {

    private final List<AuditLogger> auditLoggers;
    private final List<SyncConnector> syncConnectors;
    private final Instance<AuditLogger> auditLoggerHandle;

    @Inject
    public ExtensionRegistry(
            @All List<AuditLogger> auditLoggers,
            @All List<SyncConnector> syncConnectors,
            Instance<AuditLogger> auditLoggerHandle) {
        this.auditLoggers = auditLoggers;
        this.syncConnectors = syncConnectors;
        this.auditLoggerHandle = auditLoggerHandle;
    }

    /** The highest-priority discovered audit logger. Never {@code null}. */
    public AuditLogger activeAuditLogger() {
        return auditLoggers.get(0);
    }

    /** The highest-priority discovered sync connector. Never {@code null}. */
    public SyncConnector activeSyncConnector() {
        return syncConnectors.get(0);
    }

    /** Ids of every discovered audit logger, active first. */
    public List<String> discoveredAuditLoggerIds() {
        return auditLoggers.stream().map(AuditLogger::id).toList();
    }

    /** Ids of every discovered sync connector, active first. */
    public List<String> discoveredSyncConnectorIds() {
        return syncConnectors.stream().map(SyncConnector::id).toList();
    }

    /**
     * A snapshot for operators/diagnostics: which implementation is live for
     * each extension point and everything else that was discovered.
     *
     * <p>{@code auditLoggerBeanCount} comes from the {@link Instance} handle's
     * {@code stream()}, which — like {@code @All} — honours runtime lookup
     * gates such as {@code @LookupIfProperty}. So a present-but-unlicensed
     * commercial bean is not counted, matching what actually gets used.</p>
     */
    public ExtensionReport report() {
        return new ExtensionReport(
                activeAuditLogger().id(),
                discoveredAuditLoggerIds(),
                activeSyncConnector().id(),
                activeSyncConnector().available(),
                discoveredSyncConnectorIds(),
                auditLoggerHandle.stream().count());
    }

    public record ExtensionReport(
            String activeAuditLogger,
            List<String> discoveredAuditLoggers,
            String activeSyncConnector,
            boolean syncAvailable,
            List<String> discoveredSyncConnectors,
            long auditLoggerBeanCount) {
    }
}
