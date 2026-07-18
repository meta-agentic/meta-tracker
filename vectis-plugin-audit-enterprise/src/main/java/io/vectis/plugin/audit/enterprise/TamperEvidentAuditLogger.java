package io.vectis.plugin.audit.enterprise;

import io.vectis.extension.spi.AuditEvent;
import io.vectis.extension.spi.AuditLogger;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Example commercial audit logger: maintains a tamper-evident hash chain so
 * each recorded event is bound to its predecessor.
 *
 * <p>Two gates make this fail-closed for licensing:</p>
 * <ul>
 *   <li>{@link LookupIfProperty} — the bean is only active when
 *       {@code vectis.enterprise.audit.enabled=true}, i.e. a licensed
 *       deployment opts in. Absent or false, the community floor is used.</li>
 *   <li>{@link Priority} above the community default, so once active it is the
 *       highest-priority {@code AuditLogger} the registry discovers.</li>
 * </ul>
 *
 * <p>Prototype note: the chain lives in memory and is not persisted, signed, or
 * externally shipped — that hardening is out of scope for the spike.</p>
 */
@ApplicationScoped
@Priority(100)
@LookupIfProperty(name = "vectis.enterprise.audit.enabled", stringValue = "true")
public class TamperEvidentAuditLogger implements AuditLogger {

    private static final String GENESIS = "0".repeat(64);

    private volatile String head = GENESIS;

    @Override
    public synchronized void record(AuditEvent event) {
        String payload = head + '|' + event.tenantId() + '|' + event.actor()
                + '|' + event.action() + '|' + event.at();
        head = sha256(payload);
    }

    @Override
    public String id() {
        return "enterprise-tamper-evident";
    }

    /** Current chain head — exposed so the chain can be verified/audited. */
    public String chainHead() {
        return head;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
