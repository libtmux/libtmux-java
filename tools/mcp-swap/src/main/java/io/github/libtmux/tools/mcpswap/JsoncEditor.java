package io.github.libtmux.tools.mcpswap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class JsoncEditor {
    private static final String WHITESPACE = " \t\n\r";

    private JsoncEditor() {}

    static String merge(String original, ObjectNode desired, ObjectMapper mapper) {
        if (original.isBlank()) {
            return render(desired, 0, mapper) + "\n";
        }
        var text = original;
        for (int attempt = 0; attempt < 10_000; attempt++) {
            var edit = nextEdit(text, desired, List.of(), mapper);
            if (edit == null) {
                return text;
            }
            text = text.substring(0, edit.start()) + edit.replacement() + text.substring(edit.end());
        }
        throw new IllegalStateException("JSONC merge did not converge");
    }

    private static @Nullable Edit nextEdit(String text, ObjectNode desired, List<String> path, ObjectMapper mapper) {
        var blanked = blankComments(text);
        var span = objectSpan(blanked, path, mapper);
        if (span == null) {
            return null;
        }
        var members = new Scanner(blanked, mapper).readMembers(span.start());
        Map<String, Member> byKey = new LinkedHashMap<>();
        members.forEach(member -> byKey.put(member.key(), member));
        var depth = path.size() + 1;
        var pad = "  ".repeat(depth);

        for (var field : desired.properties()) {
            var member = byKey.get(field.getKey());
            if (member == null) {
                var body = render(field.getValue(), depth, mapper);
                var name = jsonString(field.getKey(), mapper);
                if (!members.isEmpty()) {
                    var tail = members.getLast().end();
                    return new Edit(tail, tail, ",\n" + pad + name + ": " + body);
                }
                if (!blanked.substring(span.start() + 1, span.end() - 1).trim().isEmpty()) {
                    return null;
                }
                var interior = text.substring(span.start() + 1, span.end() - 1);
                var trailingWhitespace =
                        interior.length() - interior.stripTrailing().length();
                var anchor = span.end() - 1 - trailingWhitespace;
                var closing = "  ".repeat(depth - 1);
                return new Edit(anchor, span.end() - 1, "\n" + pad + name + ": " + body + "\n" + closing);
            }

            var current = parseValue(blanked.substring(member.valueStart(), member.valueEnd()), mapper);
            if (field.getValue() instanceof ObjectNode child && current instanceof ObjectNode) {
                var nestedPath = new ArrayList<>(path);
                nestedPath.add(field.getKey());
                var nested = nextEdit(text, child, List.copyOf(nestedPath), mapper);
                if (nested != null) {
                    return nested;
                }
            } else if (!current.equals(field.getValue())) {
                return new Edit(member.valueStart(), member.valueEnd(), render(field.getValue(), depth, mapper));
            }
        }

        for (int index = 0; index < members.size(); index++) {
            var member = members.get(index);
            if (desired.has(member.key())) {
                continue;
            }
            if (index > 0) {
                return new Edit(members.get(index - 1).end(), member.end(), "");
            }
            var trailing = blanked.substring(member.end(), span.end());
            var dropTo = member.end();
            var stripped = trailing.stripLeading();
            if (stripped.startsWith(",")) {
                dropTo += trailing.indexOf(',') + 1;
            }
            return new Edit(span.start() + 1, dropTo, "");
        }
        return null;
    }

    private static @Nullable Span objectSpan(String text, List<String> path, ObjectMapper mapper) {
        var scanner = new Scanner(text, mapper);
        scanner.skipWhitespace();
        if (scanner.position() >= text.length() || text.charAt(scanner.position()) != '{') {
            return null;
        }
        var cursor = scanner.position();
        for (var key : path) {
            Member match = null;
            for (var member : new Scanner(text, mapper).readMembers(cursor)) {
                if (member.key().equals(key)) {
                    match = member;
                    break;
                }
            }
            if (match == null || text.charAt(match.valueStart()) != '{') {
                return null;
            }
            cursor = match.valueStart();
        }
        var tail = new Scanner(text, mapper);
        tail.setPosition(cursor);
        return tail.readValue();
    }

    private static String render(JsonNode value, int depth, ObjectMapper mapper) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value)
                    .replace("\n", "\n" + "  ".repeat(depth));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("cannot render JSONC", error);
        }
    }

    private static JsonNode parseValue(String text, ObjectMapper mapper) {
        try {
            return mapper.readTree(blankTrailingCommas(text));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("cannot parse JSONC value", error);
        }
    }

    private static String jsonString(String value, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("cannot render JSONC key", error);
        }
    }

    private static String blankComments(String text) {
        var output = text.toCharArray();
        var position = 0;
        var inString = false;
        while (position < text.length()) {
            var character = text.charAt(position);
            if (inString) {
                if (character == '\\') {
                    position = Math.min(text.length(), position + 2);
                } else {
                    if (character == '"') {
                        inString = false;
                    }
                    position++;
                }
            } else if (character == '"') {
                inString = true;
                position++;
            } else if (character == '/' && position + 1 < text.length() && text.charAt(position + 1) == '/') {
                while (position < text.length() && text.charAt(position) != '\n') {
                    output[position++] = ' ';
                }
            } else if (character == '/' && position + 1 < text.length() && text.charAt(position + 1) == '*') {
                var closing = text.indexOf("*/", position + 2);
                var end = closing == -1 ? text.length() : closing + 2;
                while (position < end) {
                    if (output[position] != '\n') {
                        output[position] = ' ';
                    }
                    position++;
                }
            } else {
                position++;
            }
        }
        return new String(output);
    }

    private static String blankTrailingCommas(String text) {
        var output = text.toCharArray();
        var position = 0;
        var inString = false;
        var lastComma = -1;
        while (position < text.length()) {
            var character = text.charAt(position);
            if (inString) {
                if (character == '\\') {
                    position = Math.min(text.length(), position + 2);
                    continue;
                }
                if (character == '"') {
                    inString = false;
                }
                position++;
                continue;
            }
            if (character == '"') {
                inString = true;
                lastComma = -1;
            } else if (character == ',') {
                lastComma = position;
            } else if (character == '}' || character == ']') {
                if (lastComma != -1) {
                    output[lastComma] = ' ';
                }
                lastComma = -1;
            } else if (WHITESPACE.indexOf(character) == -1) {
                lastComma = -1;
            }
            position++;
        }
        return new String(output);
    }

    private record Edit(int start, int end, String replacement) {}

    private record Span(int start, int end) {}

    private record Member(String key, int start, int end, int valueStart, int valueEnd) {}

    private static final class Scanner {
        private final String text;
        private final ObjectMapper mapper;
        private int position;

        Scanner(String text, ObjectMapper mapper) {
            this.text = text;
            this.mapper = mapper;
        }

        int position() {
            return position;
        }

        void setPosition(int position) {
            this.position = position;
        }

        void skipWhitespace() {
            while (position < text.length() && WHITESPACE.indexOf(text.charAt(position)) != -1) {
                position++;
            }
        }

        String readString() {
            var start = position++;
            while (position < text.length()) {
                var character = text.charAt(position++);
                if (character == '\\') {
                    position++;
                } else if (character == '"') {
                    break;
                }
            }
            return text.substring(start, position);
        }

        Span readValue() {
            skipWhitespace();
            var start = position;
            var character = text.charAt(position);
            if (character == '"') {
                readString();
            } else if (character == '{' || character == '[') {
                readContainer();
            } else {
                while (position < text.length()
                        && ",}]".indexOf(text.charAt(position)) == -1
                        && WHITESPACE.indexOf(text.charAt(position)) == -1) {
                    position++;
                }
            }
            return new Span(start, position);
        }

        List<Member> readMembers(int start) {
            position = start + 1;
            List<Member> found = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) == '}') {
                    return List.copyOf(found);
                }
                if (text.charAt(position) == ',') {
                    position++;
                    continue;
                }
                var memberStart = position;
                var rawKey = readString();
                skipWhitespace();
                position++;
                var value = readValue();
                found.add(new Member(decodeKey(rawKey), memberStart, value.end(), value.start(), value.end()));
            }
        }

        private void readContainer() {
            position++;
            var depth = 1;
            while (position < text.length() && depth > 0) {
                var character = text.charAt(position);
                if (character == '"') {
                    readString();
                    continue;
                }
                if (character == '{' || character == '[') {
                    depth++;
                } else if (character == '}' || character == ']') {
                    depth--;
                }
                position++;
            }
        }

        private String decodeKey(String raw) {
            try {
                return mapper.readValue(raw, String.class);
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("invalid JSONC object key", error);
            }
        }
    }
}
