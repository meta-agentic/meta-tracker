// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import java.util.Map;
import java.util.Objects;

/**
 * Create an item in a workspace. Its key is minted: {@code <workspace key>-<n>}.
 *
 * @param workspace the workspace's key
 * @param board     the key of the board to put it on, or null for the workspace's first board
 * @param column    the key of the column, or null for the board's first column
 * @param title     its title; required
 * @param rank      where in the column, or null for the rank the number in its key gives it
 * @param fields    its open fields; a field set to null is left out
 * @param origin    the writer's tag, recorded on the event: the channel, or the client that names itself
 */
public record CreateItem(String workspace, String board, String column, String title, String rank,
                         Map<String, Object> fields, String origin) {

    public CreateItem {
        Objects.requireNonNull(workspace, "workspace");
    }
}
