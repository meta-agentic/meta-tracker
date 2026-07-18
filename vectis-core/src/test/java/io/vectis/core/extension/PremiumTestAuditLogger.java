package io.vectis.core.extension;

import io.vectis.extension.spi.AuditEvent;
import io.vectis.extension.spi.AuditLogger;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for a commercial audit plugin, scoped to the test classpath.
 *
 * <p>It mirrors the real plugin's mechanism exactly: a plain
 * higher-{@code @Priority} bean gated by {@link LookupIfProperty}. It is NOT an
 * {@code @Alternative} — a selected alternative would <em>shadow</em> the
 * community bean out of {@code @All}/{@code Instance} enumeration, which is not
 * how two co-existing plain beans behave. Gating by a property keeps it inert
 * in the fallback test and active only under {@link EnterpriseSelectedProfile},
 * so both the "gated off -> floor only" and "gated on -> plugin wins, floor
 * still present" paths are exercised faithfully.</p>
 */
@ApplicationScoped
@Priority(100)
@LookupIfProperty(name = "vectis.test.premium.enabled", stringValue = "true")
public class PremiumTestAuditLogger implements AuditLogger {

    private final List<AuditEvent> recorded = new ArrayList<>();

    @Override
    public void record(AuditEvent event) {
        recorded.add(event);
    }

    @Override
    public String id() {
        return "premium-test";
    }

    public List<AuditEvent> recorded() {
        return recorded;
    }
}
