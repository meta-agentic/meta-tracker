// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * Extension point: a read-only source of work items — a backlog kept somewhere
 * other than Vectis (files in a repository, another tracker) that a workspace can
 * mirror.
 *
 * <p>This is deliberately a sibling of {@link SyncConnector}, not a widening of it.
 * A sync connector is resolved as the single active implementation over a no-op
 * fallback, whereas several backlog sources may legitimately coexist; they are
 * discovered as a list and selected by {@link #id()}, and an empty list is a valid
 * state. Write-back is not part of this contract: a future bidirectional connector
 * implements both interfaces.</p>
 *
 * <p>A source reports what it says, in its own vocabulary, plus a portable
 * {@link StatusCategory}. It never decides a board column or touches engine types;
 * mapping a {@link BacklogSnapshot} onto a workspace is the importer's job.</p>
 */
public interface BacklogSource {

    /** Stable identifier used to select this source, e.g. {@code "vault"}. */
    String id();

    /**
     * Whether this source is configured and its backing store is readable right now.
     * A {@code false} here means {@link #read()} would fail as a whole.
     */
    boolean available();

    /**
     * Reads the whole backlog as one snapshot.
     *
     * <p>Blocking: callers on an event loop must move this call to a worker thread.
     * Individual entries that cannot be read are skipped or degraded and listed in
     * {@link BacklogSnapshot#problems()}; they never fail the read.</p>
     *
     * @throws BacklogSourceException if the source cannot be read at all
     */
    BacklogSnapshot read();
}
