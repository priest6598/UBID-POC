package com.karnataka.ubid.report;

import java.util.List;
import java.util.Map;

/** Tiny dependency-free JSON serializer — handles maps, lists, strings, numbers, booleans, null. */
public final class JsonWriter {

    private JsonWriter() {}

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb, 0);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb, int depth) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { sb.append(quote(s)); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, depth + 1);
                sb.append(quote(String.valueOf(e.getKey()))).append(": ");
                write(e.getValue(), sb, depth + 1);
                if (++i < m.size()) sb.append(",");
                sb.append("\n");
            }
            indent(sb, depth);
            sb.append("}");
            return;
        }
        if (o instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, depth + 1);
                write(list.get(i), sb, depth + 1);
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            indent(sb, depth);
            sb.append("]");
            return;
        }
        // Fallback: toString
        sb.append(quote(o.toString()));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }
}
