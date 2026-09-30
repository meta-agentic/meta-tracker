// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * The portable meaning of a source status, independent of the source's own
 * vocabulary — the fallback an importer uses to place an item whose verbatim status
 * it does not recognise.
 *
 * <p>There are exactly three: work not begun, work under way, and work that has ended.
 * An ended item also carries an {@link Outcome}, which says whether anything was
 * delivered. A report that counts delivery uses {@code outcome == DELIVERED}, never
 * the category alone.</p>
 */
public enum StatusCategory {

    /**
     * Work not begun. This spans raw, planned and refined items, where refined means
     * curated well enough to be ready to work. Sources tell these apart by their verbatim
     * status, not by category, and an importer maps a verbatim status such as
     * {@code REFINED} to a specific state of its own.
     */
    START_STATE,

    /** Being worked on or reviewed. */
    IN_PROGRESS,

    /**
     * Terminal: the work has ended and will not move again. Its {@link Outcome} says
     * whether anything was delivered.
     */
    END_STATE
}
