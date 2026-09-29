// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.StatusCategory;

/**
 * The item directories of a vault space. The directory an item sits in is its
 * status tier, so it decides the category whenever the {@code status:} field is
 * missing, unknown or disagrees with it.
 *
 * <p>Declaration order is precedence: when the same key appears in two tiers (a
 * stale copy left behind by a move), the later tier wins.</p>
 */
enum Tier {

    RAW("raw", StatusCategory.NOT_STARTED),
    WIKI("wiki", StatusCategory.IN_PROGRESS),
    OUTPUT("output", StatusCategory.DONE);

    private final String directory;
    private final StatusCategory category;

    Tier(String directory, StatusCategory category) {
        this.directory = directory;
        this.category = category;
    }

    String directory() {
        return directory;
    }

    StatusCategory category() {
        return category;
    }
}
