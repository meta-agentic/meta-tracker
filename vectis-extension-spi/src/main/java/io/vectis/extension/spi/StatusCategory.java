// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * The portable meaning of a source status, independent of the source's own
 * vocabulary — the fallback an importer uses to place an item whose verbatim status
 * it does not recognise.
 *
 * <p>It mirrors the status categories most trackers expose, with two additions.
 * {@link #REFINED} is a not-started item that has been refined and is ready to be
 * pulled, normally in the next sprint; keeping it apart from {@link #NOT_STARTED}
 * lets a board show refined work in its own column instead of mixing it into the
 * raw backlog. {@link #DISCONTINUED} is a second end state beside {@link #DONE}:
 * work that stopped without being completed. A source without such notions simply
 * never reports them.</p>
 */
public enum StatusCategory {

    /** Not started and not yet ready to pull. */
    NOT_STARTED,

    /**
     * Not started, but refined and ready to pull. A consumer whose model has no refined
     * category folds this to {@link #NOT_STARTED}, and should prefer the verbatim source
     * status when it can map that more precisely.
     */
    REFINED,

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
