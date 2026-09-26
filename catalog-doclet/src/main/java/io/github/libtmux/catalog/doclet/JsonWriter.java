package io.github.libtmux.catalog.doclet;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Writes a tree of {@link Map}, {@link List}, {@link String}, {@link Boolean}, and {@link Number}
 * values as indented JSON, byte-stable across runs given the same input.
 *
 * <p>Map iteration order is preserved as the key order, so a caller building the tree with a
 * {@link java.util.LinkedHashMap} controls the output order directly.
 */
final class JsonWriter {

    private JsonWriter() {}

    static void write(Object value, Writer out) throws IOException {
        writeValue(value, out, 0);
        out.write('\n');
    }

    private static void writeValue(Object value, Writer out, int indent) throws IOException {
        switch (value) {
            case Map<?, ?> map -> writeObject(map, out, indent);
            case List<?> list -> writeArray(list, out, indent);
            case String s -> writeString(s, out);
            case Boolean b -> out.write(b.toString());
            case Number n -> out.write(n.toString());
            case null -> out.write("null");
            default -> throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
        }
    }

    private static void writeObject(Map<?, ?> map, Writer out, int indent) throws IOException {
        if (map.isEmpty()) {
            out.write("{}");
            return;
        }
        out.write("{\n");
        int i = 0;
        int last = map.size() - 1;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(out, indent + 1);
            writeString(String.valueOf(entry.getKey()), out);
            out.write(": ");
            writeValue(entry.getValue(), out, indent + 1);
            out.write(i++ == last ? "\n" : ",\n");
        }
        indent(out, indent);
        out.write("}");
    }

    private static void writeArray(List<?> list, Writer out, int indent) throws IOException {
        if (list.isEmpty()) {
            out.write("[]");
            return;
        }
        out.write("[\n");
        int last = list.size() - 1;
        for (int i = 0; i < list.size(); i++) {
            indent(out, indent + 1);
            writeValue(list.get(i), out, indent + 1);
            out.write(i == last ? "\n" : ",\n");
        }
        indent(out, indent);
        out.write("]");
    }

    private static void indent(Writer out, int depth) throws IOException {
        out.write("  ".repeat(depth));
    }

    private static void writeString(String s, Writer out) throws IOException {
        out.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.write("\\\"");
                case '\\' -> out.write("\\\\");
                case '\n' -> out.write("\\n");
                case '\r' -> out.write("\\r");
                case '\t' -> out.write("\\t");
                case '\b' -> out.write("\\b");
                case '\f' -> out.write("\\f");
                default -> {
                    if (c < 0x20) {
                        out.write(String.format("\\u%04x", (int) c));
                    } else {
                        out.write(c);
                    }
                }
            }
        }
        out.write('"');
    }
}
