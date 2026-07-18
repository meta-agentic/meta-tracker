package io.vectis.extension.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable record of something auditable that happened in Vectis (a board
 * mutation, a workflow transition, a permission change).
 *
 * <p>Part of the framework-free extension contract. A plugin never constructs
 * engine types to observe an event — it only sees this record.</p>
 *
 * @param tenantId  the tenant the event belongs to; never {@code null}
 * @param actor     the principal that caused the event; never {@code null}
 * @param action    a stable action key, e.g. {@code "board.item.moved"}
 * @param at        when the event occurred
 * @param attributes free-form, non-sensitive context; never {@code null}
 */
public record AuditEvent(
        String tenantId,
        String actor,
        String action,
        Instant at,
        Map<String, String> attributes) {

    public AuditEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(at, "at");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AuditEvent of(String tenantId, String actor, String action) {
        return new AuditEvent(tenantId, actor, action, Instant.now(), Map.of());
    }
}
