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
 * {@link StatusCategory#END_STATE} with {@link Outcome#DISCONTINUED}, because a rejected
 * or abandoned item is closed without being done. {@code NO GO} still belongs in
 * {@code raw/}, so it is not a contradiction there. {@code DONE} ends with
 * {@link Outcome#DELIVERED}. {@code REFINED} is not started, like the rest of
 * {@code raw/}; what sets it apart is carried by the verbatim status, not the category.</p>
 */
enum VaultStatus {

    TO_DO("TO DO", Tier.RAW, StatusCategory.START_STATE, null),
    PLANNED("PLANNED", Tier.RAW, StatusCategory.START_STATE, null),
    REFINED("REFINED", Tier.RAW, StatusCategory.START_STATE, null),
    NO_GO("NO GO", Tier.RAW, StatusCategory.END_STATE, Outcome.DISCONTINUED),
    IN_PROGRESS("IN PROGRESS", Tier.WIKI, StatusCategory.IN_PROGRESS, null),
    IN_REVIEW("IN REVIEW", Tier.WIKI, StatusCategory.IN_PROGRESS, null),
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
}
