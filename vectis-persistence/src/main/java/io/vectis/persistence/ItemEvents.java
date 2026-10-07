package io.vectis.persistence;

import io.vectis.domain.Item;
import io.vectis.persistence.WorkspaceEventLog.Event;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The item events of the real-time transport's event contract.
 *
 * <p>Item events carry the item's full post-change state, so a client converges by keeping the
 * highest {@code version} whatever order representations reach it in. {@code changed} names
 * the properties that changed, for the remote-change highlight; it is a hint, never applied.
 */
final class ItemEvents {

    static final String CREATED = "item.created";
    static final String UPDATED = "item.updated";
    static final String MOVED = "item.moved";
    static final String DELETED = "item.deleted";
    static final String RESYNC = "resync";

    private ItemEvents() {}

    static Event created(Item item, OffsetDateTime at) {
        List<String> changed = new ArrayList<>(List.of("key", "boardId", "columnId", "title", "rank", "fields"));
        if (item.sprintId() != null) {
            changed.add("sprintId");
        }
        return itemEvent(CREATED, item, at, changed);
    }

    static Event moved(Item item, OffsetDateTime at) {
        return itemEvent(MOVED, item, at, List.of("columnId", "rank"));
    }

    static Event sprintAssigned(Item item, OffsetDateTime at) {
        return itemEvent(UPDATED, item, at, List.of("sprintId"));
    }

    /** A title or field edit; {@code changed} is worked out from the row as it was before. */
    static Event edited(Item item, String oldTitle, Map<String, Object> oldFields, OffsetDateTime at) {
        List<String> changed = new ArrayList<>();
        if (!item.title().equals(oldTitle)) {
            changed.add("title");
        }
        var keys = new TreeSet<>(oldFields.keySet());
        keys.addAll(item.fields().keySet());
        for (String key : keys) {
            if (!Objects.equals(oldFields.get(key), item.fields().get(key))) {
                changed.add("fields." + key);
            }
        }
        return itemEvent(UPDATED, item, at, changed);
    }

    /** A tombstone: the client removes the item and drops any representation at or below this version. */
    static Event deleted(UUID id, String key, long version) {
        return new Event(DELETED, new JsonObject()
                .put("item", new JsonObject().put("id", id.toString()).put("key", key).put("version", version)));
    }

    /** A write that changed too many items to describe one by one: watching clients reload once. */
    static Event bulk() {
        return new Event(RESYNC, new JsonObject().put("reason", "bulk"));
    }

    private static Event itemEvent(String type, Item item, OffsetDateTime at, List<String> changed) {
        return new Event(type, new JsonObject()
                .put("item", representation(item, at))
                .put("changed", new JsonArray(List.copyOf(changed))));
    }

    /** The item as the client holds it; {@code updatedAt} is the writing transaction's time. */
    static JsonObject representation(Item item, OffsetDateTime at) {
        return new JsonObject()
                .put("id", item.id().toString())
                .put("key", item.key())
                .put("boardId", item.boardId().toString())
                .put("columnId", item.columnId().toString())
                .put("title", item.title())
                .put("rank", item.rank())
                .put("fields", new JsonObject(item.fields()))
                .put("sprintId", item.sprintId() == null ? null : item.sprintId().toString())
                .put("version", item.version())
                .put("updatedAt", WorkspaceEventLog.formatAt(at));
    }
}
