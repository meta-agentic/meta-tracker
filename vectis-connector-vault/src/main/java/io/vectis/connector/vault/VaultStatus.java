// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.StatusCategory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The statuses a vault item may carry, the tier each belongs in, and the portable
 * category and outcome each reports.
 *
 * <p>The category is the tier's, with one exception: {@code NO GO} reports
 * {@link StatusCategory#END_STATE} with {@link Outcome#DISCONTINUED}. A {@code NO GO} item
 * was aborted: it is closed, and it never counts as a delivered increment. It belongs in
 * {@code wiki/}, where it documents the work partly done and why it stopped, so it is not a
 * contradiction there. {@code DONE} ends with {@link Outcome#DELIVERED}. {@code REFINED} is
 * not started, like the rest of {@code raw/}; what sets it apart is carried by the verbatim
 * status, not the category.</p>
 *
 * <p>A status found outside its tier normally yields to the directory. {@code NO GO} does
 * not (see {@link #placesAnywhere()}): wherever it is filed, the item is placed as ended
 * and discontinued, never as delivered or not started, and reported as misfiled.</p>
 */
enum VaultStatus {

    TO_DO("TO DO", Tier.RAW, StatusCategory.START_STATE, null),
    PLANNED("PLANNED", Tier.RAW, StatusCategory.START_STATE, null),
    REFINED("REFINED", Tier.RAW, StatusCategory.START_STATE, null),
    IN_PROGRESS("IN PROGRESS", Tier.WIKI, StatusCategory.IN_PROGRESS, null),
    IN_REVIEW("IN REVIEW", Tier.WIKI, StatusCategory.IN_PROGRESS, null),
    NO_GO("NO GO", Tier.WIKI, StatusCategory.END_STATE, Outcome.DISCONTINUED),
    DONE("DONE", Tier.OUTPUT, StatusCategory.END_STATE, Outcome.DELIVERED);

    private final String label;
    private final Tier tier;
    private final StatusCategory category;
    private final Outcome outcome;

    VaultStatus(String label, Tier tier, StatusCategory category, Outcome outcome) {
        this.label = label;
        this.tier = tier;
        this.category = category;
        this.outcome = outcome;
    }

    /** Matches a front-matter status, ignoring case and surrounding whitespace. */
    static Optional<VaultStatus> of(String status) {
        String wanted = status.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.label.equals(wanted)).findFirst();
    }

    String label() {
        return label;
    }

    Tier tier() {
        return tier;
    }

    StatusCategory category() {
        return category;
    }

    /** How an item with this status ended; {@code null} unless the category is an end state. */
    Outcome outcome() {
        return outcome;
    }

    /**
     * Whether this status places an item even when it is filed outside its tier, instead of
     * yielding to the directory. Only {@code NO GO} does: an aborted item must never be
     * counted as delivered or as not started because of where it sits.
     */
    boolean placesAnywhere() {
        return this == NO_GO;
    }

    /** The placement as a problem reports it, e.g. {@code END_STATE/DISCONTINUED}. */
    String placement() {
        return Tier.placement(category, outcome);
    }
}
