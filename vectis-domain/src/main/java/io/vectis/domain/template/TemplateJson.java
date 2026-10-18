package io.vectis.domain.template;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small JSON tree template documents are handled as, with its reader, its canonical writer
 * and RFC 7396 merge patch.
 *
 * <p>The domain module carries no framework dependency, so templates are plain trees: object
 * → {@code LinkedHashMap<String, Object>}, array → {@code ArrayList<Object>}, string →
 * {@code String}, integer → {@code Long}, other number → {@code BigDecimal}, true/false →
 * {@code Boolean}, null → {@code null}. A null member is kept as a present key, because in a
 * delta it is a tombstone and must survive a parse/write round trip.
 */
public final class TemplateJson {

    /** Deeper input is refused rather than risking the reader's stack; a template is four levels deep. */
    static final int MAX_DEPTH = 64;

    private TemplateJson() {
    }

    /** Parses one JSON value. Malformed input, duplicate keys and trailing data are refused. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        try {
            p.ws();
            Object v = p.value(0);
            p.ws();
            if (p.i != text.length()) {
                throw p.error("trailing data");
            }
            return v;
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw p.error("malformed JSON");
        }
    }

    /** Parses a JSON object. */
    public static Map<String, Object> parseObject(String text) {
        if (!(parse(text) instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return obj(m);
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH);
            }
            return switch (s.charAt(i)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> out = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return out;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                if (out.containsKey(key)) {
                    throw error("duplicate key " + key);
                }
                out.put(key, value(depth + 1));
                ws();
                if (peek() != ',') {
                    expect('}');
                    return out;
                }
                i++;
            }
        }

        List<Object> array(int depth) {
            List<Object> out = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return out;
            }
            while (true) {
                ws();
                out.add(value(depth + 1));
                ws();
                if (peek() != ',') {
                    expect(']');
                    return out;
                }
                i++;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            for (char c = s.charAt(i++); c != '"'; c = s.charAt(i++)) {
                if (c < 0x20) {
                    throw error("unescaped control character");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    case '"', '\\', '/' -> sb.append(e);
                    default -> throw error("invalid escape \\" + e);
                }
            }
            return sb.toString();
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (!n.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) {
                throw error("unexpected character");
            }
            return n.matches("-?\\d+") ? (Object) Long.valueOf(n) : new BigDecimal(n);
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw error("expected " + word);
            }
            i += word.length();
            return v;
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at offset " + i);
        }
    }

    /**
     * The canonical pretty form the shipped templates and golden files are written in: arrays of
     * scalars and short all-scalar objects on one line, everything else one member per line, two
     * spaces of indent and a final newline.
     */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, 0);
        return sb.append('\n').toString();
    }

    private static void write(Object v, StringBuilder sb, int depth) {
        if (v instanceof Map<?, ?> m && !m.isEmpty() && !fitsInline(v)) {
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append("  ".repeat(depth + 1)).append(compact(e.getKey())).append(": ");
                write(e.getValue(), sb, depth + 1);
                sb.append(++n < m.size() ? ",\n" : "\n");
            }
            sb.append("  ".repeat(depth)).append('}');
        } else if (v instanceof List<?> l && !fitsInline(v)) {
            sb.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                sb.append("  ".repeat(depth + 1));
                write(l.get(k), sb, depth + 1);
                sb.append(k + 1 < l.size() ? ",\n" : "\n");
            }
            sb.append("  ".repeat(depth)).append(']');
        } else {
            sb.append(compact(v));
        }
    }

    private static boolean fitsInline(Object v) {
        if (v instanceof List<?> l) {
            return scalars(l);
        }
        for (Object o : ((Map<?, ?>) v).values()) {
            if (o instanceof Map<?, ?> || o instanceof List<?> l && !scalars(l)) {
                return false;
            }
        }
        return compact(v).length() <= 72;
    }

    private static boolean scalars(List<?> l) {
        return l.stream().noneMatch(o -> o instanceof Map<?, ?> || o instanceof List<?>);
    }

    /** The one-line form, used inline by {@link #write} and in violation messages. */
    public static String compact(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String str) {
            return quote(str);
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            m.forEach((k, x) -> sb.append(sb.length() > 1 ? ", " : "").append(compact(k)).append(": ").append(compact(x)));
            return sb.append('}').toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            l.forEach(x -> sb.append(sb.length() > 1 ? ", " : "").append(compact(x)));
            return sb.append(']').toString();
        }
        return v.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }

    /**
     * RFC 7396 section 2, verbatim: an object patch merges member by member, a null member
     * removes the target member, and any non-object patch replaces the target whole. Arrays are
     * values, never merged element by element. Neither input is modified.
     */
    static Object mergePatch(Object target, Object patch) {
        if (!(patch instanceof Map<?, ?> p)) {
            return deepCopy(patch);
        }
        Map<String, Object> result = target instanceof Map<?, ?> ? obj(deepCopy(target)) : new LinkedHashMap<>();
        p.forEach((k, x) -> {
            if (x == null) {
                result.remove((String) k);
            } else {
                result.put((String) k, mergePatch(result.get(k), x));
            }
        });
        return result;
    }

    static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put((String) k, deepCopy(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(deepCopy(x)));
            return out;
        }
        return v;
    }

    /** The value as an object, an absent one as empty. The caller has checked the shape. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    /** The value as a mutable copy of a string list, an absent one (or any other shape) as empty. */
    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(x -> out.add(x instanceof String s ? s : String.valueOf(x)));
        }
        return out;
    }
}
