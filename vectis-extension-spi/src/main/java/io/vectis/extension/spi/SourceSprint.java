// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A sprint as a {@link BacklogSource} records it. Dates are the source's own and are
 * mirrored as history, not replayed as transitions.
 *
 * @param key    the sprint's identity in the source, e.g. {@code "VEC-S3"}; never blank
 * @param state  where the source says the sprint is; never {@code null}
 * @param start  planned or actual start; {@code null} when the source has none
 * @param end    planned end; {@code null} when the source has none
 * @param closed when the sprint was closed; {@code null} unless the source recorded it
 */
public record SourceSprint(String key, State state, LocalDate start, LocalDate end, LocalDate closed) {

    /** The sprint's lifecycle state in the source. */
    public enum State { FUTURE, ACTIVE, CLOSED }

    public SourceSprint {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        if (key.isBlank()) {
            throw new IllegalArgumentException("sprint key must not be blank");
        }
    }
}
