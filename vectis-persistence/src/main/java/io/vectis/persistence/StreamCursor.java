package io.vectis.persistence;

import java.util.Objects;
import java.util.UUID;

/**
 * A position in one workspace's event stream: {@code <epoch>.<seq>}, the SSE {@code id} and
 * the {@code Last-Event-ID} a client sends back.
 *
 * <p>{@code seq} alone is not enough. A database restore rewinds every workspace's
 * {@code event_seq}, and the restore procedure gives every workspace a new
 * {@code stream_epoch}; a cursor whose epoch is not the workspace's current one is from
 * before the restore, and its holder must reload rather than be replayed from a
 * {@code seq} that now names different events.
 */
public record StreamCursor(UUID epoch, long seq) {

    public StreamCursor {
        Objects.requireNonNull(epoch, "epoch");
        if (seq < 0) {
            throw new IllegalArgumentException("stream seq must not be negative: " + seq);
        }
    }

    /**
     * Parses {@code <epoch>.<seq>}. The epoch is a canonical uuid, which has no dot, so the
     * split is at the only one.
     *
     * @throws IllegalArgumentException if {@code text} is not a cursor
     */
    public static StreamCursor parse(String text) {
        Objects.requireNonNull(text, "text");
        int dot = text.indexOf('.');
        if (dot < 0 || dot != text.lastIndexOf('.')) {
            throw new IllegalArgumentException("not a stream cursor: " + text);
        }
        String epoch = text.substring(0, dot);
        try {
            UUID parsed = UUID.fromString(epoch);
            // UUID.fromString is lenient ("1-2-3-4-5" parses); a cursor carries the canonical form.
            if (!parsed.toString().equalsIgnoreCase(epoch)) {
                throw new IllegalArgumentException("not a canonical uuid: " + epoch);
            }
            return new StreamCursor(parsed, Long.parseLong(text.substring(dot + 1)));
        } catch (IllegalArgumentException e) { // NumberFormatException included
            throw new IllegalArgumentException("not a stream cursor: " + text, e);
        }
    }

    @Override
    public String toString() {
        return epoch + "." + seq;
    }
}
