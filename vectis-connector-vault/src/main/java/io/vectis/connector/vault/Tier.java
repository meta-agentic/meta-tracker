// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.StatusCategory;

/**
 * The item directories of a vault space. The directory an item sits in is its
 * status tier, so it decides the category whenever the {@code status:} field is
 * missing, unknown or disagrees with it. An item placed in {@code output/} that way
 * is taken as {@link Outcome#DELIVERED}: that directory holds delivered work, and a
 * discontinued item is recorded by its status, {@code NO GO}, not by where it sits.
 *
 * <p>Declaration order is precedence: when the same key appears in two tiers (a
 * stale copy left behind by a move), the later tier wins.</p>
 */
enum Tier {

    RAW("raw", StatusCategory.START_STATE, null),
    WIKI("wiki", StatusCategory.IN_PROGRESS, null),
    OUTPUT("output", StatusCategory.END_STATE, Outcome.DELIVERED);

    private final String directory;
    private final StatusCategory category;
    private final Outcome outcome;

    Tier(String directory, StatusCategory category, Outcome outcome) {
        this.directory = directory;
        this.category = category;
        this.outcome = outcome;
    }

    String directory() {
        return directory;
    }

    StatusCategory category() {
        return category;
    }

    /** The outcome of an item this tier places; {@code null} unless the category is an end state. */
    Outcome outcome() {
        return outcome;
    }

    /** The placement as a problem reports it, e.g. {@code END_STATE/DELIVERED}. */
    String placement() {
        return outcome == null ? category.name() : category + "/" + outcome;
    }
}
