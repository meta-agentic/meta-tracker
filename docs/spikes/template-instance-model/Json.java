// Minimal JSON reader/writer and RFC 7396 merge patch for the template-instance spike.
//
// Throwaway, like the rest of this directory: zero dependencies so the spike runs by
// source launch on a bare JDK. Production would use Jackson (already on the server
// classpath) or Jakarta JSON-P, whose Json.createMergePatch implements the same RFC.
//
// Values map to: object -> LinkedHashMap<String,Object>, array -> ArrayList<Object>,
// string -> String, integer -> Long, other number -> BigDecimal, true/false -> Boolean,
// null -> null. A JSON null member is kept as a present key with a null value, because
// in a delta that null is meaningful (a tombstone) and must survive parse/write.

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {

    private Json() {
    }

    // ---- reading ------------------------------------------------------

    static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw p.error("trailing data");
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
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
                out.put(key, value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        List<Object> array() {
            List<Object> out = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return out;
            }
            while (true) {
                ws();
                out.add(value());
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
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
                    default -> sb.append(e);
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (n.isEmpty()) {
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
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at offset " + i);
        }
    }

    // ---- writing ------------------------------------------------------

    /**
     * Canonical pretty form, the one the checked-in .json files are written in: arrays of
     * scalars and short all-scalar objects on one line, everything else one member per line.
     */
    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, 0);
        return sb.append('\n').toString();
    }

    private static void write(Object v, StringBuilder sb, int depth) {
        if (v instanceof Map<?, ?> m && !m.isEmpty() && !fitsInline(v)) {
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, depth + 1);
                sb.append(compact(e.getKey())).append(": ");
                write(e.getValue(), sb, depth + 1);
                sb.append(++n < m.size() ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append('}');
        } else if (v instanceof List<?> l && !fitsInline(v)) {
            sb.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                indent(sb, depth + 1);
                write(l.get(k), sb, depth + 1);
                sb.append(k + 1 < l.size() ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append(']');
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

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    /** One-line form, used inline and in diagnostics. */
    static String compact(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String str) {
            return '"' + str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(sb.length() > 1 ? ", " : "").append(compact(e.getKey())).append(": ").append(compact(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : l) {
                sb.append(sb.length() > 1 ? ", " : "").append(compact(o));
            }
            return sb.append(']').toString();
        }
        return v.toString();
    }

    // ---- RFC 7396 merge patch -----------------------------------------

    /**
     * RFC 7396 section 2, verbatim: an object patch merges member by member, a null
     * member removes the target member, and any non-object patch replaces the target
     * whole. Arrays are therefore values, never merged element by element.
     */
    static Object mergePatch(Object target, Object patch) {
        if (!(patch instanceof Map<?, ?> p)) {
            return deepCopy(patch);
        }
        Map<String, Object> result = target instanceof Map<?, ?> ? obj(deepCopy(target)) : new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : p.entrySet()) {
            String k = (String) e.getKey();
            if (e.getValue() == null) {
                result.remove(k);
            } else {
                result.put(k, mergePatch(result.get(k), e.getValue()));
            }
        }
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object v) {
        return v == null ? new LinkedHashMap<>() : (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object v) {
        return v == null ? new ArrayList<>() : new ArrayList<>((List<String>) v);
    }
}
