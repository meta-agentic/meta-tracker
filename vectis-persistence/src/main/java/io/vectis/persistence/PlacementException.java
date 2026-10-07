package io.vectis.persistence;

/**
 * Thrown when a write would place a row where it does not belong: an item on a board of
 * another workspace, in a column of another board, or a sprint completion moving items to a
 * sprint that is not an open sprint of the same board. Each of these would otherwise commit,
 * and its event would describe the item in a stream that does not hold it.
 */
public class PlacementException extends RuntimeException {

    public PlacementException(String message) {
        super(message);
    }
}
