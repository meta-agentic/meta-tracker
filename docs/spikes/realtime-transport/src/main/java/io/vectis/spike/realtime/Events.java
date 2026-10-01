package io.vectis.spike.realtime;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The event contract, as code. Every event is one JSON object with the same envelope:
 * {@code type}, {@code workspaceId}, {@code seq}, {@code at}, {@code origin}, then a body
 * that depends on the type. Item events carry the item's full post-change state, so they
 * are idempotent and commute under "highest version wins". Configuration events are
 * invalidations carrying only the new revision, because the effective configuration is
 * large, changes rarely, and an ancestor publish fans out to many workspaces.
 *
 * <p>Keys are inserted in a fixed order ({@link JsonObject} keeps insertion order), so the
 * bytes on the wire are deterministic.
 */
final class Events {

    static final String ITEM_COLUMNS =
            "id, key, board_id, column_id, title, rank, fields, sprint_id, version, updated_at";

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    /** An origin is an opaque client-instance tag echoed back so a tab can tell its own writes. */
    private static final Pattern ORIGIN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private Events() {}

    static String origin(String header) {
        return header != null && ORIGIN.matcher(header).matches() ? header : null;
    }

    static JsonObject item(Row row) {
        JsonObject fields = row.getJsonObject("fields");
        UUID sprint = row.getUUID("sprint_id");
        return new JsonObject()
                .put("id", row.getUUID("id").toString())
                .put("key", row.getString("key"))
                .put("boardId", row.getUUID("board_id").toString())
                .put("columnId", row.getUUID("column_id").toString())
                .put("title", row.getString("title"))
                .put("rank", row.getString("rank"))
                .put("fields", fields == null ? new JsonObject() : fields)
                .put("sprintId", sprint == null ? null : sprint.toString())
                .put("version", row.getLong("version"))
                .put("updatedAt", at(row.getOffsetDateTime("updated_at")));
    }

    static String at(OffsetDateTime time) {
        return time.withOffsetSameInstant(ZoneOffset.UTC).format(AT);
    }

    private static JsonObject envelope(String type, UUID workspaceId, long seq, String at, String origin) {
        return new JsonObject()
                .put("type", type)
                .put("workspaceId", workspaceId.toString())
                .put("seq", seq)
                .put("at", at)
                .put("origin", origin);
    }

    /** {@code item.created}, {@code item.moved} or {@code item.updated}: the full item, plus what changed. */
    static String itemEvent(String type, UUID workspaceId, long seq, String origin, JsonObject item, List<String> changed) {
        return envelope(type, workspaceId, seq, item.getString("updatedAt"), origin)
                .put("item", item)
                .put("changed", new JsonArray(changed))
                .encode();
    }

    /**
     * {@code configuration.changed}: an invalidation. The client re-reads the configuration
     * (VEC-45's {@code GET .../configuration}, ETag = revision) when {@code revision} is
     * above the one it holds. {@code cause} says why, so the UI can word the notice.
     */
    static String configurationChanged(UUID workspaceId, long seq, String at, String origin, long revision, JsonObject cause) {
        return envelope("configuration.changed", workspaceId, seq, at, origin)
                .put("revision", revision)
                .put("cause", cause)
                .encode();
    }

    /** {@code resync}: the stream cannot be replayed from the client's cursor; re-read the snapshot. */
    static String resync(UUID workspaceId, long seq, String at, String reason) {
        return envelope("resync", workspaceId, seq, at, null)
                .put("reason", reason)
                .encode();
    }
}
