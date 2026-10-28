// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.extension.spi.SourceItem;
import io.vectis.persistence.ImportedItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A source item as the persisted model holds it: its column, a rank that follows the number in
 * its key, and the open fields the web client reads ({@code web/src/api/adapter.ts}). A field the
 * source leaves empty is left out, never written as null.
 */
final class ImportedItems {

    private static final int RANK_WIDTH = 8;

    private ImportedItems() {
    }

    static ImportedItem of(SourceItem item) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("type", item.kind());
        fields.put("status", item.status());
        put(fields, "parentId", item.epicKey());
        put(fields, "priority", item.priority());
        put(fields, "description", item.body());
        if (item.storyPoints() != null) {
            double points = item.storyPoints();
            fields.put("storyPoints", points == Math.rint(points) ? (Object) (long) points : (Object) points);
        }
        put(fields, "labels", item.labels());
        put(fields, "dependencies", item.links().get(SourceItem.DEPENDS_ON));
        put(fields, "relates", item.links().get(SourceItem.RELATES_TO));
        return new ImportedItem(item.key(), item.title(), ColumnPlacement.columnKey(item), rank(item.key()), fields);
    }

    /** The key's number, zero-padded, so that VEC-9 sorts before VEC-10. */
    static String rank(String key) {
        String number = key.substring(key.lastIndexOf('-') + 1);
        return "0".repeat(Math.max(0, RANK_WIDTH - number.length())) + number;
    }

    private static void put(Map<String, Object> fields, String name, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(name, value);
        }
    }

    private static void put(Map<String, Object> fields, String name, List<String> values) {
        if (values != null && !values.isEmpty()) {
            fields.put(name, new ArrayList<>(values));
        }
    }
}
