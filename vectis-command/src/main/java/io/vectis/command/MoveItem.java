// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import java.util.Map;
import java.util.Objects;

/**
 * Move an item to a column of its board, at a rank, setting on the way the fields the target state
 * needs.
 *
 * @param expectedVersion the version the move is made against; null is refused
 * @param column          the key of the target column, on the item's board; required
 * @param rank            the rank in that column, or null to keep the item's rank
 * @param fields          a JSON merge patch (RFC 7396) of the open fields, written with the move as
 *                        one version and one event; null or empty changes no field
 */
public record MoveItem(String workspace, String item, Long expectedVersion, String column, String rank,
                       Map<String, Object> fields, String origin) {

    public MoveItem {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(item, "item");
    }

    /** A move that sets no field. */
    public MoveItem(String workspace, String item, Long expectedVersion, String column, String rank, String origin) {
        this(workspace, item, expectedVersion, column, rank, null, origin);
    }
}
