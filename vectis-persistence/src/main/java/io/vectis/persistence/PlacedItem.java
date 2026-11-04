// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.Item;

/** An item and the keys of the board, the column and the sprint (null in the backlog) it is in, as one read returns them. */
public record PlacedItem(Item item, String boardKey, String columnKey, String sprintKey) {
}
