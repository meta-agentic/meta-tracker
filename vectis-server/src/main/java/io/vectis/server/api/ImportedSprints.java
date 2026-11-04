// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.domain.SprintStatus;
import io.vectis.extension.spi.SourceSprint;
import io.vectis.persistence.ImportedSprint;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * The sprints of a source as the board holds them: FUTURE, ACTIVE and CLOSED become FUTURE, ACTIVE
 * and COMPLETED. A board has one active sprint, so when a source says several are active the latest
 * stays active and the others are imported as completed, with a problem reported.
 */
final class ImportedSprints {

    /** Later start wins; sprints that start on the same day or have no start are told apart by the key's number. */
    private static final Comparator<SourceSprint> LATEST = Comparator
            .comparing((SourceSprint s) -> s.start() == null ? LocalDate.MIN : s.start())
            .thenComparing(s -> s.key().length()).thenComparing(SourceSprint::key);

    private ImportedSprints() {
    }

    /** What to write, and what to report about the sprints; {@code problems} is added to by the caller. */
    record Plan(List<ImportedSprint> sprints, List<String> problems) {
    }

    static Plan of(List<SourceSprint> sprints) {
        List<SourceSprint> active = sprints.stream().filter(s -> s.state() == SourceSprint.State.ACTIVE).toList();
        SourceSprint latest = active.stream().max(LATEST).orElse(null);
        List<String> problems = active.size() < 2 ? List.of() : List.of("sprints " + active.stream()
                .map(SourceSprint::key).sorted().toList() + " are all active; only " + latest.key()
                + ", the latest, is imported as active");
        List<ImportedSprint> out = sprints.stream().map(s -> new ImportedSprint(s.key(), status(s, latest),
                s.start(), s.end(), s.closed(), s.goal())).toList();
        return new Plan(out, problems);
    }

    private static SprintStatus status(SourceSprint sprint, SourceSprint latestActive) {
        return switch (sprint.state()) {
            case FUTURE -> SprintStatus.FUTURE;
            case ACTIVE -> sprint == latestActive ? SprintStatus.ACTIVE : SprintStatus.COMPLETED;
            case CLOSED -> SprintStatus.COMPLETED;
        };
    }
}
