package com.larsons.engine.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser.
 *
 * <p>Keeping JSON parsing in-engine (rather than pulling in a library) is what
 * lets the engine stay runnable on any machine with only a JDK installed
 * (requirement #4: functional out of the box). It is a small recursive-descent
 * parser supporting the full JSON grammar.
 *
 * <p>Parsed values map to plain Java types:
 * <ul>
 *   <li>object  -&gt; {@code Map<String,Object>} (insertion-ordered)</li>
 *   <li>array   -&gt; {@code List<Object>}</li>
 *   <li>string  -&gt; {@code String}</li>
 *   <li>number  -&gt; {@code Double}</li>
 *   <li>boolean -&gt; {@code Boolean}</li>
 *   <li>null    -&gt; {@code null}</li>
 * </ul>
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** Parse a JSON document into a tree of Maps/Lists/primitives. */
    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i < j.s.length()) throw j.err("Trailing characters after JSON value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) { return (List<Object>) o; }

    // --- Writing -------------------------------------------------------------

    /**
     * Serialize a tree of {@code Map}/{@code Iterable}/{@code String}/
     * {@code Number}/{@code Boolean}/{@code null} into pretty-printed JSON
     * (2-space indent). Used to persist game-type profiles.
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int indent) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof String str) { writeString(sb, str); return; }
        if (value instanceof Boolean b) { sb.append(b.booleanValue()); return; }
        if (value instanceof Number n) { writeNumber(sb, n); return; }
        if (value instanceof Map<?, ?> m) { writeObject(sb, m, indent); return; }
        if (value instanceof Iterable<?> it) { writeArray(sb, it, indent); return; }
        writeString(sb, String.valueOf(value)); // fallback
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int indent) {
        if (m.isEmpty()) { sb.append("{}"); return; }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            write(sb, e.getValue(), indent + 1);
            if (++i < m.size()) sb.append(',');
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it, int indent) {
        java.util.Iterator<?> iter = it.iterator();
        if (!iter.hasNext()) { sb.append("[]"); return; }
        sb.append("[\n");
        while (iter.hasNext()) {
            indent(sb, indent + 1);
            write(sb, iter.next(), indent + 1);
            if (iter.hasNext()) sb.append(',');
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        // Doubles/floats keep a decimal point; integral types print plainly.
        if (n instanceof Double || n instanceof Float) {
            sb.append(n.doubleValue());
        } else {
            sb.append(n.longValue());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int k = 0; k < level; k++) sb.append("  ");
    }

    // --- Parsing -------------------------------------------------------------

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't':
            case 'f': return bool();
            case 'n': return nul();
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        expect('{');
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') break;
            if (c != ',') throw err("Expected ',' or '}' in object");
        }
        return m;
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        expect('[');
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = next();
            if (c == ']') break;
            if (c != ',') throw err("Expected ',' or ']' in array");
        }
        return a;
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        String hex = s.substring(i, i + 4);
                        i += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    default: throw err("Invalid escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        if (num.isEmpty()) throw err("Unexpected character '" + peek() + "'");
        return Double.parseDouble(num);
    }

    private Object bool() {
        if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("Invalid literal");
    }

    private Object nul() {
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw err("Invalid literal");
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private char peek() {
        if (i >= s.length()) throw err("Unexpected end of input");
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw err("Expected '" + c + "'");
    }

    private RuntimeException err(String msg) {
        return new IllegalArgumentException("JSON error at index " + i + ": " + msg);
    }
}
