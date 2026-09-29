// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * The portable meaning of a source status, independent of the source's own
 * vocabulary — the fallback an importer uses to place an item whose verbatim status
 * it does not recognise.
 *
 * <p>It mirrors the status categories most trackers expose, with one addition:
 * {@link #REFINED}, a not-started item that has been refined and is ready to be
 * pulled, normally in the next sprint. Keeping it apart from {@link #NOT_STARTED}
 * lets a board show refined work in its own column instead of mixing it into the
 * raw backlog. A source without such a notion simply never reports it.</p>
 */
public enum StatusCategory {

    /** Not started and not yet ready to pull. */
    NOT_STARTED,

    /** Not started, but refined and ready to pull. */
    REFINED,

    /** Being worked on or reviewed. */
    IN_PROGRESS,

    /** Finished — delivered or otherwise closed. */
    DONE
}
