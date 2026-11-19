// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import java.util.Objects;

/**
 * Delete an item.
 *
 * @param expectedVersion the version the deletion is made against; null is refused
 */
public record DeleteItem(String workspace, String item, Long expectedVersion, String origin) {

    public DeleteItem {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(item, "item");
    }
}
