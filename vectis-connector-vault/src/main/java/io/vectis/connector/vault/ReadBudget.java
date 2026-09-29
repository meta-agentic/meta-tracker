// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

/**
 * What one read of a vault may consume in total. Each file is already capped; this caps
 * the whole, so a vault flooded with files cannot exhaust memory or time. Once spent,
 * the reader stops and marks the snapshot incomplete.
 */
final class ReadBudget {

    private static final long MIB = 1024L * 1024;

    private final int maxEntries;
    private final long maxBytes;
    private int entries;
    private long bytes;
    private String exceeded;

    ReadBudget(int maxEntries, long maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /** How many more directory entries may be listed before the budget is spent. */
    int remainingEntries() {
        return Math.max(0, maxEntries - entries);
    }

    void listed(int count) {
        entries += count;
        if (entries > maxEntries && exceeded == null) {
            exceeded = "more than " + maxEntries + " directory entries";
        }
    }

    void read(long count) {
        bytes += count;
        if (bytes > maxBytes && exceeded == null) {
            exceeded = maxBytes >= MIB
                    ? "more than " + maxBytes / MIB + " MiB of files"
                    : "more than " + maxBytes + " bytes of files";
        }
    }

    boolean exhausted() {
        return exceeded != null;
    }

    /** Why the budget ran out; {@code null} while it has not. */
    String exceeded() {
        return exceeded;
    }
}
