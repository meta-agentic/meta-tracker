// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.SourceItem;
import java.util.Locale;

/**
 * The column of the Scrum template an imported item
 * belongs in. The connector decides what an item is: its category, and for an end state its
 * outcome. This only names the column that category and outcome land in; where a category has
 * several columns the verbatim status says which.
 *
 * <ul>
 *   <li>start state: {@code PLANNED} is {@code todo}, any other ({@code TO DO}, {@code REFINED},
 *       which is a backlog item that meets the definition of ready and is told apart by a label
 *       ({@link ImportedItems#REFINED_LABEL}), or a status the connector placed by its directory)
 *       is {@code backlog};
 *   <li>in progress: {@code IN REVIEW} is {@code in-review}, any other is {@code in-progress};
 *   <li>end state: delivered is {@code done}, discontinued is {@code no-go}.
 * </ul>
 */
final class ColumnPlacement {

    static final String BACKLOG = "backlog";
    static final String TODO = "todo";
    static final String IN_PROGRESS = "in-progress";
    static final String IN_REVIEW = "in-review";
    static final String DONE = "done";
    static final String NO_GO = "no-go";

    private ColumnPlacement() {
    }

    static String columnKey(SourceItem item) {
        String status = item.status().strip().toUpperCase(Locale.ROOT);
        return switch (item.category()) {
            case START_STATE -> switch (status) {
                case "PLANNED" -> TODO;
                default -> BACKLOG;
            };
            case IN_PROGRESS -> status.equals("IN REVIEW") ? IN_REVIEW : IN_PROGRESS;
            case END_STATE -> item.outcome() == Outcome.DISCONTINUED ? NO_GO : DONE;
        };
    }
}
