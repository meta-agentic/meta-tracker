// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.StatusCategory;

/**
 * The item directories of a vault space. The directory an item sits in is its
 * status tier, so it decides the category whenever the {@code status:} field is
 * missing, unknown or disagrees with it. An item placed in {@code output/} that way
 * is taken as {@link Outcome#DELIVERED}, since that directory holds delivered work.
 *
 * <p>The one status the directory never overrides is {@code NO GO}. An aborted item is
 * never a delivered increment and never not started, so wherever it is filed it is placed
 * as ended and discontinued, and reported as misfiled when it is not in {@code wiki/}.</p>
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
        return placement(category, outcome);
    }

    static String placement(StatusCategory category, Outcome outcome) {
        return outcome == null ? category.name() : category + "/" + outcome;
    }
}
