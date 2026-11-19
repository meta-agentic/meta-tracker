// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import java.util.Map;
import java.util.Objects;

/**
 * Change an item's title and open fields. Column and rank change by {@link MoveItem}.
 *
 * @param expectedVersion the version the change is made against; null is refused
 * @param title           the new title, or null to keep it
 * @param fields          a JSON merge patch (RFC 7396) of the open fields: a member set to null
 *                        removes that field, an object merges into an object, anything else
 *                        replaces; null or empty changes no field
 */
public record EditItem(String workspace, String item, Long expectedVersion, String title,
                       Map<String, Object> fields, String origin) {

    public EditItem {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(item, "item");
    }
}
