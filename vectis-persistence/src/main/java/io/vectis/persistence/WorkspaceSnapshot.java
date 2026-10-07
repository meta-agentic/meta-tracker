package io.vectis.persistence;

import io.vectis.domain.Item;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A workspace's items together with the stream position they are current to, read in one
 * statement and so from one database snapshot: every event at or below {@code cursor.seq()}
 * is reflected in {@code items}, and none above it is.
 *
 * <p>A client replaces its state with this, then opens the stream after {@code cursor}.
 */
public record WorkspaceSnapshot(UUID workspaceId, StreamCursor cursor, List<Item> items) {

    public WorkspaceSnapshot {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cursor, "cursor");
        items = List.copyOf(items);
    }
}
