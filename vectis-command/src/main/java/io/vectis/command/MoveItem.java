// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import java.util.Objects;

/**
 * Move an item to a column of its board, at a rank.
 *
 * @param expectedVersion the version the move is made against; null is refused
 * @param column          the key of the target column, on the item's board; required
 * @param rank            the rank in that column, or null to keep the item's rank
 */
public record MoveItem(String workspace, String item, Long expectedVersion, String column, String rank,
                       String origin) {

    public MoveItem {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(item, "item");
    }
}
