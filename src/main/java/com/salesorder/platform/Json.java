package com.salesorder.platform;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编解码器：项目保持零第三方依赖，前后端统一用 Map/List 表达数据。
 * 支持对象、数组、字符串、数字、布尔、null，以及 Unicode 转义序列。
 */
public final class Json {
    private Json() {}

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
                sb.append("null");
            } else {
                sb.append(n);
            }
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                write(sb, Array.get(value, i));
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON text is null");
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (!p.end()) throw new IllegalArgumentException("JSON has trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public static List<Object> arr(Object... values) {
        List<Object> l = new ArrayList<>();
        for (Object v : values) l.add(v);
        return l;
    }

    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m == null ? null : m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    public static int integer(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static long lng(Object v, long fallback) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static double dbl(Object v, double fallback) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static boolean bool(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        if (v instanceof Number n) return n.intValue() != 0;
        return fallback;
    }

    /** 深拷贝，用于仓储层事务回滚快照。 */
    public static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), copy(e.getValue()));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(copy(o));
            return out;
        }
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean end() { return pos >= s.length(); }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            ws();
            if (end()) throw new IllegalArgumentException("JSON ended unexpectedly");
            char c = s.charAt(pos);
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

        private Object literal(String word, Object result) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("Bad literal at " + pos);
            pos += word.length();
            return result;
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            ws();
            if (!end() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws();
                if (end() || s.charAt(pos) != '"') throw new IllegalArgumentException("Expected key at " + pos);
                String k = string();
                ws();
                if (end() || s.charAt(pos) != ':') throw new IllegalArgumentException("Expected ':' at " + pos);
                pos++;
                m.put(k, value());
                ws();
                if (end()) throw new IllegalArgumentException("Unclosed object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at " + (pos - 1));
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            ws();
            if (!end() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (end()) throw new IllegalArgumentException("Unclosed array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at " + (pos - 1));
            }
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (!end()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (end()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unclosed string");
        }

        private Number number() {
            int start = pos;
            if (!end() && s.charAt(pos) == '-') pos++;
            while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            boolean decimal = false;
            if (!end() && s.charAt(pos) == '.') {
                decimal = true;
                pos++;
                while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (!end() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (!end() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (!end() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String raw = s.substring(start, pos);
            if (raw.isEmpty() || "-".equals(raw)) throw new IllegalArgumentException("Bad number at " + start);
            try {
                return decimal ? Double.valueOf(raw) : Long.valueOf(raw);
            } catch (NumberFormatException ex) {
                return Double.valueOf(raw);
            }
        }
    }
}
