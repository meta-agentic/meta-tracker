// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

/**
 * Making untrusted text safe to echo in a problem report. Problems end up in logs and
 * API responses, so a value from a vault file must not be able to forge a log line,
 * smuggle a terminal escape sequence, or inflate the report.
 */
final class Text {

    static final int MAX_ECHO = 80;

    private static final String ELLIPSIS = "\u2026";

    private Text() {
    }

    /** The value in single quotes, as {@link #bounded(String)} renders it. */
    static String quote(String value) {
        return "'" + bounded(value) + "'";
    }

    /** The value cut to {@value #MAX_ECHO} characters, marked with an ellipsis if cut, and escaped. */
    static String bounded(String value) {
        String shown = value;
        if (value.length() > MAX_ECHO) {
            int cut = Character.isHighSurrogate(value.charAt(MAX_ECHO - 1)) ? MAX_ECHO - 1 : MAX_ECHO;
            shown = value.substring(0, cut) + ELLIPSIS;
        }
        return escape(shown);
    }

    /**
     * Escapes what could break a report onto a new line or disguise it: control characters
     * (C0, DEL, C1), the Unicode line and paragraph separators, format characters such as
     * bidirectional overrides and zero-width marks, and lone surrogates. A backslash is
     * doubled, so every escape in the result is one this method made.
     */
    static String escape(String value) {
        var out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (unsafe(c)) {
                        for (char unit : Character.toChars(c)) {
                            out.append(String.format("\\u%04X", (int) unit));
                        }
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    private static boolean unsafe(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.FORMAT, Character.SURROGATE -> true;
            default -> false;
        };
    }
}
