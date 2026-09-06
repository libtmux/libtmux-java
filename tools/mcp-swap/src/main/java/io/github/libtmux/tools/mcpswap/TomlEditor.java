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

    static byte[] update(byte[] original, String tableName, String serverName, ServerSpec server) {
        var text = new String(original, StandardCharsets.UTF_8);
        var existing = read(original, tableName, serverName)
                .map(ServerSpec::environment)
                .orElseGet(Map::of);
        var merged = server.withEnvironment(existing);
        var newline = text.contains("\r\n") ? "\r\n" : "\n";
        var sections = sections(text);
        var target = List.of(tableName, serverName);
        var start = -1;
        var end = -1;
        for (var section : sections) {
            if (startsWith(section.path(), target)) {
                if (start == -1) {
                    start = section.start();
                }
                end = section.end();
            } else if (start != -1) {
                break;
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
        if (start == -1) {
            var separator = text.isEmpty() || text.endsWith(newline + newline)
                    ? ""
                    : text.endsWith(newline) ? newline : newline + newline;
            updated = text + separator + "[" + tableName + "." + string(serverName) + "]" + newline + body;
        } else {
            var comments = commentsOnly(text.substring(start, end));
            var replacement = "[" + tableName + "." + string(serverName) + "]" + newline + body + comments;
            if (!replacement.endsWith(newline)) {
                replacement += newline;
            }
            updated = text.substring(0, start) + replacement + text.substring(end);
        }
        requireValid(updated);
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    static Optional<ServerSpec> read(byte[] raw, String tableName, String serverName) {
        var result = requireValid(new String(raw, StandardCharsets.UTF_8));
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
}
