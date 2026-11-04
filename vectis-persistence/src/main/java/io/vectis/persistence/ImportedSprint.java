// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.SprintStatus;
import java.time.LocalDate;
import java.util.Objects;

/** A sprint as an import brings it: named by its source key, with the dates and goal the source records. */
public record ImportedSprint(String key, SprintStatus status, LocalDate start, LocalDate end, LocalDate closed,
                             String goal) {

    public ImportedSprint {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        if (key.isBlank()) {
            throw new IllegalArgumentException("sprint key must not be blank");
        }
    }
}
