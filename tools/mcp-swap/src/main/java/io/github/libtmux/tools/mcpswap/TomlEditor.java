package io.github.libtmux.tools.mcpswap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

final class TomlEditor {
    private static final ObjectMapper STRINGS = new ObjectMapper();

    private TomlEditor() {}

    static byte[] update(
            byte[] original, String tableName, String serverName, ServerSpec server, boolean mergeEnvironment) {
        var text = ConfigCodec.decodeUtf8(original, "TOML config");
        var parsed = requireValid(text);
        var existing = read(original, tableName, serverName)
                .map(ServerSpec::environment)
                .orElseGet(Map::of);
        var merged = mergeEnvironment ? server.withEnvironment(existing) : server;
        var inline = inlineValue(text, parsed, tableName, serverName);
        if (inline != null) {
            var updated = text.substring(0, inline.start()) + inlineEntry(merged) + text.substring(inline.end());
            requireValid(updated);
            return updated.getBytes(StandardCharsets.UTF_8);
        }
        var dotted = dottedAssignments(text, parsed, tableName, serverName);
        if (!dotted.isEmpty()) {
            var edited = new StringBuilder(text);
            for (var range : dotted.reversed()) {
                edited.delete(range.start(), range.end());
            }
            text = edited.toString();
        }
        var newline = text.contains("\r\n") ? "\r\n" : "\n";
        var sections = sections(text);
        var target = List.of(tableName, serverName);
        List<Section> matches = new ArrayList<>();
        for (var section : sections) {
            if (startsWith(section.path(), target)) {
                matches.add(section);
            }
        }
        var body = "command = " + string(merged.command()) + newline + "args = " + array(merged.arguments()) + newline;
        if (!merged.environment().isEmpty()) {
            body += newline + "[" + tableName + "." + string(serverName) + ".env]" + newline;
            for (var value : merged.environment().entrySet()) {
                body += string(value.getKey()) + " = " + string(value.getValue()) + newline;
            }
        }
        String updated;
        if (matches.isEmpty()) {
            var separator = text.isEmpty() || text.endsWith(newline + newline)
                    ? ""
                    : text.endsWith(newline) ? newline : newline + newline;
            updated = text + separator + "[" + tableName + "." + string(serverName) + "]" + newline + body;
        } else {
            var replacement = "[" + tableName + "." + string(serverName) + "]" + newline + body;
            var edited = new StringBuilder(text);
            for (int index = matches.size() - 1; index >= 0; index--) {
                var section = matches.get(index);
                var comments = commentsOnly(text.substring(section.start(), section.end()));
                var contents = index == 0 ? replacement + comments : comments;
                if (index == 0 && !contents.endsWith(newline)) {
                    contents += newline;
                }
                edited.replace(section.start(), section.end(), contents);
            }
            updated = edited.toString();
        }
        requireValid(updated);
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    static Optional<ServerSpec> read(byte[] raw, String tableName, String serverName) {
        var result = requireValid(ConfigCodec.decodeUtf8(raw, "TOML config"));
        var table = result.getTable(tableName);
        if (table == null) {
            return Optional.empty();
        }
        var server = table.getTable(List.of(serverName));
        if (server == null) {
            return Optional.empty();
        }
        var command = server.getString("command");
        var arguments = server.getArray("args");
        if (command == null) {
            throw new IllegalArgumentException("server command must be present");
        }
        List<String> values = new ArrayList<>();
        if (arguments != null) {
            for (int index = 0; index < arguments.size(); index++) {
                var value = arguments.getString(index);
                if (value == null) {
                    throw new IllegalArgumentException("server args must contain only strings");
                }
                values.add(value);
            }
        }
        Map<String, String> environment = new LinkedHashMap<>();
        var rawEnvironment = server.getTable("env");
        if (rawEnvironment != null) {
            for (var key : rawEnvironment.keySet()) {
                var value = rawEnvironment.getString(key);
                if (value == null) {
                    throw new IllegalArgumentException("server environment values must be strings");
                }
                environment.put(key, value);
            }
        }
        return Optional.of(new ServerSpec(command, values, environment));
    }

    private static TomlParseResult requireValid(String text) {
        var result = Toml.parse(text);
        if (result.hasErrors()) {
            throw new IllegalArgumentException(
                    "config is not valid TOML: " + result.errors().getFirst());
        }
        return result;
    }

    private static @Nullable Range inlineValue(
            String text, TomlParseResult parsed, String tableName, String serverName) {
        var position = parsed.inputPositionOf(List.of(tableName, serverName));
        if (position == null) {
            return null;
        }
        var start = offset(text, position.line(), position.column());
        var keyEnd = simpleKeyEnd(text, start);
        if (keyEnd == start || !decodeKey(text.substring(start, keyEnd)).equals(serverName)) {
            return null;
        }
        var equals = skipWhitespace(text, keyEnd);
        if (equals >= text.length() || text.charAt(equals) != '=') {
            return null;
        }
        var valueStart = skipWhitespace(text, equals + 1);
        if (valueStart >= text.length() || text.charAt(valueStart) != '{') {
            return null;
        }
        return new Range(valueStart, closingDelimiter(text, valueStart, '{', '}') + 1);
    }

    private static List<Range> dottedAssignments(
            String text, TomlParseResult parsed, String tableName, String serverName) {
        var table = parsed.getTable(tableName);
        var server = table == null ? null : table.getTable(List.of(serverName));
        if (server == null) {
            return List.of();
        }
        Map<Integer, Range> found = new java.util.TreeMap<>();
        for (var suffix : server.keyPathSet(true)) {
            List<String> path = new ArrayList<>(List.of(tableName, serverName));
            path.addAll(suffix);
            var position = parsed.inputPositionOf(path);
            if (position == null) {
                continue;
            }
            var start = offset(text, position.line(), position.column());
            var equals = assignmentEquals(text, start);
            if (equals == -1
                    || !startsWith(dottedKeys(text.substring(start, equals)), List.of(tableName, serverName))) {
                continue;
            }
            found.put(start, new Range(start, statementEnd(text, equals + 1)));
        }
        return List.copyOf(found.values());
    }

    private static int assignmentEquals(String text, int start) {
        var quote = '\0';
        var escaped = false;
        for (int offset = start; offset < text.length(); offset++) {
            var character = text.charAt(offset);
            if (escaped) {
                escaped = false;
            } else if (quote == '"' && character == '\\') {
                escaped = true;
            } else if (quote != '\0' && character == quote) {
                quote = '\0';
            } else if (quote == '\0' && (character == '"' || character == '\'')) {
                quote = character;
            } else if (quote == '\0' && character == '=') {
                return offset;
            } else if (quote == '\0' && (character == '\n' || character == '[')) {
                return -1;
            }
        }
        return -1;
    }

    private static int statementEnd(String text, int start) {
        var nesting = 0;
        var quote = '\0';
        var triple = false;
        var escaped = false;
        for (int offset = start; offset < text.length(); offset++) {
            var character = text.charAt(offset);
            if (quote != '\0') {
                if (escaped) {
                    escaped = false;
                } else if (quote == '"' && character == '\\') {
                    escaped = true;
                } else if (character == quote
                        && (!triple
                                || (offset + 2 < text.length()
                                        && text.charAt(offset + 1) == quote
                                        && text.charAt(offset + 2) == quote))) {
                    if (triple) {
                        offset += 2;
                    }
                    quote = '\0';
                    triple = false;
                }
                continue;
            }
            if (character == '#') {
                var newline = text.indexOf('\n', offset);
                return newline == -1 ? text.length() : newline + 1;
            }
            if (character == '"' || character == '\'') {
                quote = character;
                triple = offset + 2 < text.length()
                        && text.charAt(offset + 1) == character
                        && text.charAt(offset + 2) == character;
                if (triple) {
                    offset += 2;
                }
            } else if (character == '[' || character == '{') {
                nesting++;
            } else if (character == ']' || character == '}') {
                nesting--;
            } else if (character == '\n' && nesting == 0) {
                return offset + 1;
            }
        }
        return text.length();
    }

    private static int offset(String text, int line, int column) {
        var offset = 0;
        for (int current = 1; current < line; current++) {
            var newline = text.indexOf('\n', offset);
            if (newline == -1) {
                throw new IllegalArgumentException("TOML position is outside the document");
            }
            offset = newline + 1;
        }
        return offset + column - 1;
    }

    private static int simpleKeyEnd(String text, int start) {
        if (start >= text.length()) {
            return start;
        }
        var quote = text.charAt(start);
        if (quote != '"' && quote != '\'') {
            var offset = start;
            while (offset < text.length()) {
                var character = text.charAt(offset);
                if (!(Character.isLetterOrDigit(character) || character == '_' || character == '-')) {
                    break;
                }
                offset++;
            }
            return offset;
        }
        var escaped = false;
        for (int offset = start + 1; offset < text.length(); offset++) {
            var character = text.charAt(offset);
            if (escaped) {
                escaped = false;
            } else if (quote == '"' && character == '\\') {
                escaped = true;
            } else if (character == quote) {
                return offset + 1;
            }
        }
        throw new IllegalArgumentException("unterminated quoted TOML key");
    }

    private static int skipWhitespace(String text, int start) {
        var offset = start;
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static int closingDelimiter(String text, int start, char opening, char closing) {
        var depth = 0;
        var quote = '\0';
        var triple = false;
        var escaped = false;
        for (int offset = start; offset < text.length(); offset++) {
            var character = text.charAt(offset);
            if (quote != '\0') {
                if (escaped) {
                    escaped = false;
                } else if (quote == '"' && character == '\\') {
                    escaped = true;
                } else if (character == quote
                        && (!triple
                                || (offset + 2 < text.length()
                                        && text.charAt(offset + 1) == quote
                                        && text.charAt(offset + 2) == quote))) {
                    if (triple) {
                        offset += 2;
                    }
                    quote = '\0';
                    triple = false;
                }
                continue;
            }
            if (character == '#') {
                var newline = text.indexOf('\n', offset);
                if (newline == -1) {
                    break;
                }
                offset = newline;
            } else if (character == '"' || character == '\'') {
                quote = character;
                triple = offset + 2 < text.length()
                        && text.charAt(offset + 1) == character
                        && text.charAt(offset + 2) == character;
                if (triple) {
                    offset += 2;
                }
            } else if (character == opening) {
                depth++;
            } else if (character == closing && --depth == 0) {
                return offset;
            }
        }
        throw new IllegalArgumentException("unterminated TOML value");
    }

    private static String inlineEntry(ServerSpec server) {
        var entry = new StringBuilder("{ command = ")
                .append(string(server.command()))
                .append(", args = ")
                .append(array(server.arguments()));
        if (!server.environment().isEmpty()) {
            entry.append(", env = { ");
            var separator = "";
            for (var value : server.environment().entrySet()) {
                entry.append(separator)
                        .append(string(value.getKey()))
                        .append(" = ")
                        .append(string(value.getValue()));
                separator = ", ";
            }
            entry.append(" }");
        }
        return entry.append(" }").toString();
    }

    private static List<Section> sections(String text) {
        List<Section> found = new ArrayList<>();
        var offset = 0;
        while (offset < text.length()) {
            var lineEnd = text.indexOf('\n', offset);
            var afterLine = lineEnd == -1 ? text.length() : lineEnd + 1;
            var path = tablePath(text.substring(offset, lineEnd == -1 ? text.length() : lineEnd));
            if (path != null) {
                found.add(new Section(path, offset, afterLine, text.length()));
            }
            offset = afterLine;
        }
        for (int index = 0; index + 1 < found.size(); index++) {
            var current = found.get(index);
            found.set(
                    index,
                    new Section(
                            current.path(),
                            current.start(),
                            current.headerEnd(),
                            found.get(index + 1).start()));
        }
        return List.copyOf(found);
    }

    private static @Nullable List<String> tablePath(String line) {
        var stripped = line.strip();
        if (!stripped.startsWith("[") || stripped.startsWith("[[")) {
            return null;
        }
        var closing = closingBracket(stripped);
        if (closing == -1 || !stripped.substring(closing + 1).strip().matches("(?:#.*)?")) {
            return null;
        }
        return dottedKeys(stripped.substring(1, closing));
    }

    private static int closingBracket(String text) {
        var quote = '\0';
        var escaped = false;
        for (int index = 1; index < text.length(); index++) {
            var character = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quote == '"' && character == '\\') {
                escaped = true;
            } else if (quote != '\0' && character == quote) {
                quote = '\0';
            } else if (quote == '\0' && (character == '"' || character == '\'')) {
                quote = character;
            } else if (quote == '\0' && character == ']') {
                return index;
            }
        }
        return -1;
    }

    private static List<String> dottedKeys(String raw) {
        List<String> keys = new ArrayList<>();
        var offset = 0;
        while (offset < raw.length()) {
            while (offset < raw.length() && Character.isWhitespace(raw.charAt(offset))) {
                offset++;
            }
            if (offset >= raw.length()) {
                return List.of();
            }
            var start = offset;
            var quote = raw.charAt(offset) == '"' || raw.charAt(offset) == '\'' ? raw.charAt(offset++) : '\0';
            var escaped = false;
            while (offset < raw.length()) {
                var character = raw.charAt(offset);
                if (escaped) {
                    escaped = false;
                } else if (quote == '"' && character == '\\') {
                    escaped = true;
                } else if (quote != '\0' && character == quote) {
                    offset++;
                    break;
                } else if (quote == '\0' && (character == '.' || Character.isWhitespace(character))) {
                    break;
                }
                offset++;
            }
            var token = raw.substring(start, offset).strip();
            keys.add(decodeKey(token));
            while (offset < raw.length() && Character.isWhitespace(raw.charAt(offset))) {
                offset++;
            }
            if (offset == raw.length()) {
                break;
            }
            if (raw.charAt(offset++) != '.') {
                return List.of();
            }
        }
        return List.copyOf(keys);
    }

    private static String decodeKey(String token) {
        if (token.startsWith("\"") && token.endsWith("\"")) {
            try {
                return STRINGS.readValue(token, String.class);
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("invalid quoted TOML key", error);
            }
        }
        if (token.startsWith("'") && token.endsWith("'")) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private static String commentsOnly(String body) {
        var kept = new StringBuilder();
        var offset = 0;
        while (offset < body.length()) {
            var lineEnd = body.indexOf('\n', offset);
            var afterLine = lineEnd == -1 ? body.length() : lineEnd + 1;
            var line = body.substring(offset, afterLine);
            var stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                kept.append(line);
            }
            offset = afterLine;
        }
        return kept.toString();
    }

    private static boolean startsWith(List<String> path, List<String> prefix) {
        return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    private static String array(List<String> values) {
        return values.stream().map(TomlEditor::string).collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static String string(String value) {
        try {
            return STRINGS.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("cannot quote TOML string", error);
        }
    }

    private record Section(List<String> path, int start, int headerEnd, int end) {
        Section {
            path = List.copyOf(path);
        }
    }

    private record Range(int start, int end) {}
}
