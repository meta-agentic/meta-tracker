// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.StatusCategory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The statuses a vault item may carry, the tier each belongs in, and the portable
 * category each reports.
 *
 * <p>The category is the tier's, with two exceptions, both in {@code raw/}:
 * {@code REFINED} reports {@link StatusCategory#REFINED}, because refined work is ready
 * to pull and a board shows it apart from the raw backlog; {@code NO GO} reports
 * {@link StatusCategory#DISCONTINUED}, because a rejected or abandoned item is closed
 * without being done. {@code NO GO} still belongs in {@code raw/}, so it is not a
 * contradiction there.</p>
 */
enum VaultStatus {

    TO_DO("TO DO", Tier.RAW, StatusCategory.NOT_STARTED),
    PLANNED("PLANNED", Tier.RAW, StatusCategory.NOT_STARTED),
    REFINED("REFINED", Tier.RAW, StatusCategory.REFINED),
    NO_GO("NO GO", Tier.RAW, StatusCategory.DISCONTINUED),
    IN_PROGRESS("IN PROGRESS", Tier.WIKI, StatusCategory.IN_PROGRESS),
    IN_REVIEW("IN REVIEW", Tier.WIKI, StatusCategory.IN_PROGRESS),
    DONE("DONE", Tier.OUTPUT, StatusCategory.DONE);

    private final String label;
    private final Tier tier;
    private final StatusCategory category;

    VaultStatus(String label, Tier tier, StatusCategory category) {
        this.label = label;
        this.tier = tier;
        this.category = category;
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
}
