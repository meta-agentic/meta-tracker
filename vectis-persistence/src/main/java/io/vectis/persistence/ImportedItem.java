// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.Map;
import java.util.Objects;

/**
 * An item as an import brings it: named by its key, placed by the key of the board column it
 * belongs in, with the rank it takes when created and its open fields.
 */
public record ImportedItem(String key, String title, String columnKey, String rank, Map<String, Object> fields) {

    public ImportedItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(columnKey, "columnKey");
        Objects.requireNonNull(rank, "rank");
        if (title.isBlank()) {
            throw new IllegalArgumentException("item title must not be blank");
        }
        if (rank.isBlank()) {
            throw new IllegalArgumentException("item rank must not be blank");
        }
        fields = Map.copyOf(Objects.requireNonNullElse(fields, Map.of()));
    }
}
