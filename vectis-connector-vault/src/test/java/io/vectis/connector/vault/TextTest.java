// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void escapesC0C1AndDelAndLeavesPrintableTextAlone() {
        assertEquals("a\\nb\\rc\\td\\u0000\\u001B\\u007F\\u0085\\u009Fé€",
                Text.escape("a\nb\rc\td\u0000\u001B\u007F\u0085\u009Fé€"));
    }

    @Test
    void quotesAndCutsLongValues() {
        assertEquals("'short'", Text.quote("short"));
        assertEquals("'" + "x".repeat(Text.MAX_ECHO) + "…'", Text.quote("x".repeat(Text.MAX_ECHO + 1)));
        assertEquals("'" + "x".repeat(Text.MAX_ECHO) + "'", Text.quote("x".repeat(Text.MAX_ECHO)));
    }

    @Test
    void doesNotSplitASurrogatePairWhenCutting() {
        String value = "x".repeat(Text.MAX_ECHO - 1) + "😀" + "tail";

        assertEquals("'" + "x".repeat(Text.MAX_ECHO - 1) + "…'", Text.quote(value));
    }
}
