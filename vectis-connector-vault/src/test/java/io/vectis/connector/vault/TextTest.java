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
    void escapesLineAndParagraphSeparatorsFormatCharactersAndLoneSurrogates() {
        assertEquals("a\\u2028b\\u2029c\\u202Ed\\u2066e\\u200Bf\\uFEFFg\\uDB40\\uDC41h\\uD800i",
                Text.escape("a\u2028b\u2029c\u202Ed\u2066e\u200Bf\uFEFFg\uDB40\uDC41h\uD800i"));
    }

    @Test
    void doublesABackslashSoALiteralEscapeCannotPassForAMadeOne() {
        assertEquals("a\\\\nb\\nc", Text.escape("a\\nb\nc"));
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
