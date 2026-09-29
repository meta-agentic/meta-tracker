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

    /** Escapes C0 and C1 control characters and DEL, so the result is a single printable line. */
    static String escape(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
