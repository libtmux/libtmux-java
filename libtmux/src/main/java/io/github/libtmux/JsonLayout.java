package io.github.libtmux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** tmux's restricted JSON grammar and layout cells, for pre-mutation workspace validation. */
final class JsonLayout {
    private final String text;
    private int at;

    private JsonLayout(String text) {
        this.text = text;
    }

    static int leaves(String text) {
        JsonLayout parser = new JsonLayout(text);
        Map<String, Object> root = parser.object(1);
        parser.peek();
        if (parser.at != text.length() || !Long.valueOf(2).equals(root.get("V"))) throw invalid();
        Object layout = root.get("L");
        if (layout == null) throw invalid();
        var pending = new ArrayDeque<Object>();
        pending.push(layout);
        Set<Long> indexes = new HashSet<>();
        Set<Long> last = new HashSet<>();
        Set<Long> floating = new HashSet<>();
        int active = 0;
        while (!pending.isEmpty()) {
            if (!(pending.pop() instanceof Map<?, ?> cell)) throw invalid();
            number(cell.get("w"), 1, 10_000);
            number(cell.get("h"), 1, 10_000);
            number(cell.get("x"), -10_000, 10_000);
            number(cell.get("y"), -10_000, 10_000);
            Object type = cell.get("t");
            if ("p".equals(type)) {
                if (cell.containsKey("c") || !indexes.add(number(cell.get("i"), 0, Integer.MAX_VALUE))) {
                    throw invalid();
                }
                if (cell.containsKey("a")) {
                    if (!(cell.get("a") instanceof Boolean selected)) throw invalid();
                    if (selected && ++active > 1) throw invalid();
                } else if (cell.containsKey("l") && !last.add(number(cell.get("l"), 0, Integer.MAX_VALUE))) {
                    throw invalid();
                }
                if (cell.containsKey("z") && !floating.add(number(cell.get("z"), 0, Integer.MAX_VALUE - 1L))) {
                    throw invalid();
                }
            } else if ("h".equals(type) || "v".equals(type)) {
                if (!(cell.get("c") instanceof List<?> children) || children.size() < 2) throw invalid();
                for (Object child : children) pending.push(child);
            } else throw invalid();
        }
        return indexes.size();
    }

    private static long number(@Nullable Object value, long minimum, long maximum) {
        if (!(value instanceof Long number) || number < minimum || number > maximum) throw invalid();
        return number;
    }

    private Map<String, Object> object(int depth) {
        if (depth > 200) throw invalid();
        expect('{');
        Map<String, Object> fields = new LinkedHashMap<>();
        if (take('}')) return fields;
        do {
            String key = string();
            expect(':');
            if (fields.putIfAbsent(key, value(depth)) != null) throw invalid();
        } while (take(','));
        expect('}');
        return fields;
    }

    private Object value(int depth) {
        return switch (peek()) {
            case '{' -> object(depth + 1);
            case '[' -> {
                expect('[');
                List<Map<String, Object>> members = new ArrayList<>();
                if (!take(']')) {
                    do {
                        members.add(object(depth + 1));
                    } while (take(','));
                    expect(']');
                }
                yield members;
            }
            case '"' -> string();
            case 't' -> literal("true", true);
            case 'f' -> literal("false", false);
            default -> integer();
        };
    }

    private boolean literal(String spelling, boolean value) {
        if (!text.startsWith(spelling, at)) throw invalid();
        at += spelling.length();
        return value;
    }

    private long integer() {
        int start = at;
        if (at < text.length() && text.charAt(at) == '-') at++;
        int digits = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        if (digits == at || (text.charAt(digits) == '0' && at - digits > 1)) throw invalid();
        try {
            return Long.parseLong(text.substring(start, at));
        } catch (NumberFormatException invalidNumber) {
            throw invalid();
        }
    }

    // tmux validates escapes but keeps their spelling, including in keys.
    private String string() {
        expect('"');
        int start = at;
        while (at < text.length()) {
            char ch = text.charAt(at++);
            if (ch == '"') {
                if (at - 1 == start) throw invalid();
                return text.substring(start, at - 1);
            }
            if (ch < 0x20) throw invalid();
            if (ch == '\\') {
                if (at == text.length()) throw invalid();
                char escaped = text.charAt(at++);
                if (escaped == 'u') {
                    for (int digit = 0; digit < 4; digit++) {
                        if (at == text.length() || "0123456789abcdefABCDEF".indexOf(text.charAt(at++)) < 0)
                            throw invalid();
                    }
                } else if ("\"\\/bfnrt".indexOf(escaped) < 0) throw invalid();
            }
        }
        throw invalid();
    }

    private char peek() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        return at == text.length() ? '\0' : text.charAt(at);
    }

    private boolean take(char token) {
        if (peek() != token) return false;
        at++;
        return true;
    }

    private void expect(char token) {
        if (!take(token)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("not a tmux JSON layout");
    }
}
