// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An item to create before it has a key: {@link ItemRepository#create} mints the key from the
 * workspace's counter.
 *
 * @param rank the rank to place it at, or null for the rank its key's number gives it
 */
public record NewItem(UUID workspaceId, UUID boardId, UUID columnId, String title, String rank,
                      Map<String, Object> fields) {

    public NewItem {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(boardId, "boardId");
        Objects.requireNonNull(columnId, "columnId");
        Objects.requireNonNull(title, "title");
        fields = Map.copyOf(Objects.requireNonNullElse(fields, Map.of()));
    }
}
