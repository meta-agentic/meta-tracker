// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.Item;

/** An item and the keys of the board and the column it is in, as one read returns them. */
public record PlacedItem(Item item, String boardKey, String columnKey) {
}
