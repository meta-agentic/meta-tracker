// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.StatusCategory;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The statuses a vault item may carry, the tiers each belongs in, and the portable
 * category and outcome each reports.
 *
 * <p>Every status but one belongs in a single tier and reports that tier's category.
 * The exception is {@code NO GO}, which reports {@link StatusCategory#END_STATE} with
 * {@link Outcome#DISCONTINUED}. A {@code NO GO} item was aborted: it is closed, and it
 * never counts as a delivered increment. It is never promoted, so it stays in the tier its
 * work had reached: {@code raw/} if the work never started, {@code wiki/} if it was under
 * way, where it documents the work partly done and why it stopped. Both are correct homes;
 * only {@code output/}, which holds delivered work, is not. {@code DONE} ends with
 * {@link Outcome#DELIVERED}. {@code REFINED} is not started, like the rest of {@code raw/};
 * what sets it apart is carried by the verbatim status, not the category.</p>
 *
 * <p>A status found outside its tiers normally yields to the directory. {@code NO GO} does
 * not (see {@link #placesAnywhere()}): wherever it is filed, the item is placed as ended
 * and discontinued, never as delivered or not started, and reported as misfiled when it
 * is outside its tiers.</p>
 */
enum VaultStatus {

    TO_DO("TO DO", EnumSet.of(Tier.RAW), StatusCategory.START_STATE, null),
    PLANNED("PLANNED", EnumSet.of(Tier.RAW), StatusCategory.START_STATE, null),
    REFINED("REFINED", EnumSet.of(Tier.RAW), StatusCategory.START_STATE, null),
    IN_PROGRESS("IN PROGRESS", EnumSet.of(Tier.WIKI), StatusCategory.IN_PROGRESS, null),
    IN_REVIEW("IN REVIEW", EnumSet.of(Tier.WIKI), StatusCategory.IN_PROGRESS, null),
    NO_GO("NO GO", EnumSet.of(Tier.RAW, Tier.WIKI), StatusCategory.END_STATE, Outcome.DISCONTINUED),
    DONE("DONE", EnumSet.of(Tier.OUTPUT), StatusCategory.END_STATE, Outcome.DELIVERED);

    private final String label;
    private final Set<Tier> tiers;
    private final StatusCategory category;
    private final Outcome outcome;

    VaultStatus(String label, Set<Tier> tiers, StatusCategory category, Outcome outcome) {
        this.label = label;
        this.tiers = Set.copyOf(tiers);
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

    /** The tiers an item with this status may correctly be filed in. */
    Set<Tier> tiers() {
        return tiers;
    }

    boolean belongsIn(Tier tier) {
        return tiers.contains(tier);
    }

    /** The tiers as a problem names them, in tier order, e.g. {@code raw/ or wiki/}. */
    String homes() {
        return tiers.stream().sorted().map(tier -> tier.directory() + "/").collect(Collectors.joining(" or "));
    }

    StatusCategory category() {
        return category;
    }

    /** How an item with this status ended; {@code null} unless the category is an end state. */
    Outcome outcome() {
        return outcome;
    }

    /**
     * Whether this status places an item even when it is filed outside its tiers, instead of
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
