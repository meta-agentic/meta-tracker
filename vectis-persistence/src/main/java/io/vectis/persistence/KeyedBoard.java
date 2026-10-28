// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.Board;

/** A board and the key it is addressed by, unique within its workspace (V5). */
public record KeyedBoard(String key, Board board) {
}
