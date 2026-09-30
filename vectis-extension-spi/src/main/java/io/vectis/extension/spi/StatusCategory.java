// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * The portable meaning of a source status, independent of the source's own
 * vocabulary — the fallback an importer uses to place an item whose verbatim status
 * it does not recognise.
 *
 * <p>It mirrors the status categories most trackers expose, with one addition:
 * {@link #DISCONTINUED}, a second end state beside {@link #DONE} for work that stopped
 * without being completed. A source without that notion simply never reports it.</p>
 */
public enum StatusCategory {

    /**
     * Not started. This spans raw, planned and refined items, where refined means curated
     * well enough to be ready to work. Sources tell these apart by their verbatim status,
     * not by category, and an importer maps a verbatim status such as {@code REFINED} to a
     * specific state of its own.
     */
    NOT_STARTED,

    /** Being worked on or reviewed. */
    IN_PROGRESS,

    /** Finished: the work was completed and delivered. */
    DONE,

    /**
     * A terminal state reached when work stopped without completion and has no viable
     * path forward. It is neither not-started nor done: the item is closed, but nothing
     * was delivered.
     */
    DISCONTINUED
}
