// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.SprintStatus;
import java.time.LocalDate;

/** A keyed sprint with its dates, goal and the story points it holds and has finished. */
public record SprintSummary(String key, String name, SprintStatus status, LocalDate start, LocalDate end,
                            LocalDate closed, String goal, double committedPoints, double donePoints) {
}
