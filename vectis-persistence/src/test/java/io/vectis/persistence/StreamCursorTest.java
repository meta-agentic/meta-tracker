package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The {@code <epoch>.<seq>} cursor is parsed from a header a client controls, so it is strict. */
class StreamCursorTest {

    @Test
    void roundTripsThroughItsWireForm() {
        StreamCursor cursor = new StreamCursor(UUID.fromString("01a0f568-ff3d-7f3a-abbe-1c614150e04b"), 95);

        assertEquals("01a0f568-ff3d-7f3a-abbe-1c614150e04b.95", cursor.toString());
        assertEquals(cursor, StreamCursor.parse(cursor.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "95", "01a0f568-ff3d-7f3a-abbe-1c614150e04b", "01a0f568-ff3d-7f3a-abbe-1c614150e04b.",
        "01a0f568-ff3d-7f3a-abbe-1c614150e04b.-1", "01a0f568-ff3d-7f3a-abbe-1c614150e04b.9.5",
        "01a0f568-ff3d-7f3a-abbe-1c614150e04b.x", "1-2-3-4-5.95", "not-a-uuid.95",
        "01a0f568-ff3d-7f3a-abbe-1c614150e04b.+5", "01a0f568-ff3d-7f3a-abbe-1c614150e04b.05"
    })
    void refusesAnythingElse(String text) {
        assertThrows(IllegalArgumentException.class, () -> StreamCursor.parse(text));
    }
}
