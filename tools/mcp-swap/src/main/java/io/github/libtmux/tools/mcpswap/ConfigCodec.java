package io.github.libtmux.tools.mcpswap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class ConfigCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper JSONC = new ObjectMapper(JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build());

    private ConfigCodec() {}

    static byte[] update(Client client, byte[] original, String serverName, ServerSpec server) {
        return switch (client.format()) {
            case JSON -> updateJson(client, original, serverName, server, false);
            case JSONC -> updateJson(client, original, serverName, server, true);
            case TOML -> TomlEditor.update(original, client.serverTable(), serverName, server);
        };
    }

    static Optional<ServerSpec> read(Client client, byte[] raw, String serverName) {
        return switch (client.format()) {
            case JSON -> readJson(client, raw, serverName, JSON);
            case JSONC -> readJson(client, raw, serverName, JSONC);
            case TOML -> TomlEditor.read(raw, client.serverTable(), serverName);
        };
    }

    private static byte[] updateJson(
            Client client, byte[] original, String serverName, ServerSpec server, boolean comments) {
        try {
            var mapper = comments ? JSONC : JSON;
            var text = new String(original, StandardCharsets.UTF_8);
            JsonNode parsed = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            if (!(parsed instanceof ObjectNode root)) {
                throw new IllegalArgumentException(client.name() + " config root is not an object");
            }
            var tableNode = root.get(client.serverTable());
            ObjectNode table;
            if (tableNode == null) {
                table = mapper.createObjectNode();
                root.set(client.serverTable(), table);
            } else if (tableNode instanceof ObjectNode object) {
                table = object;
            } else {
                throw new IllegalArgumentException(client.name() + " server table is not an object");
            }
            var current = table.get(serverName);
            var merged = server.withEnvironment(environment(client, current));
            table.set(serverName, entry(mapper, client, merged));
            if (comments) {
                return JsoncEditor.merge(text, root, mapper).getBytes(StandardCharsets.UTF_8);
            }
            return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(client.name() + " config is not valid JSON", error);
        }
    }

    private static ObjectNode entry(ObjectMapper mapper, Client client, ServerSpec server) {
        var entry = mapper.createObjectNode();
        if (client.openCode()) {
            entry.put("type", "local");
            var command = entry.putArray("command");
            command.add(server.command());
            server.arguments().forEach(command::add);
        } else {
            if (client.name().equals("claude")) {
                entry.put("type", "stdio");
            }
            entry.put("command", server.command());
            var arguments = entry.putArray("args");
            server.arguments().forEach(arguments::add);
        }
        if (!server.environment().isEmpty() || client.name().equals("claude")) {
            var values = entry.putObject(client.openCode() ? "environment" : "env");
            server.environment().forEach(values::put);
        }
        return entry;
    }

    private static Map<String, String> environment(Client client, JsonNode entry) {
        if (entry == null || entry.isMissingNode()) {
            return Map.of();
        }
        if (!(entry instanceof ObjectNode object)) {
            throw new IllegalArgumentException(client.name() + " server entry is not an object");
        }
        var raw = object.get(client.openCode() ? "environment" : "env");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof ObjectNode values)) {
            throw new IllegalArgumentException(client.name() + " server environment is not an object");
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (var field : values.properties()) {
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException(client.name() + " server environment values must be strings");
            }
            found.put(field.getKey(), field.getValue().textValue());
        }
        return found;
    }

    private static Optional<ServerSpec> readJson(Client client, byte[] raw, String serverName, ObjectMapper mapper) {
        try {
            var text = new String(raw, StandardCharsets.UTF_8);
            JsonNode parsed = text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            if (!(parsed instanceof ObjectNode root)) {
                throw new IllegalArgumentException(client.name() + " config root is not an object");
            }
            var table = root.get(client.serverTable());
            if (table == null) {
                return Optional.empty();
            }
            if (!(table instanceof ObjectNode servers)) {
                throw new IllegalArgumentException(client.name() + " server table is not an object");
            }
            var entry = servers.get(serverName);
            if (entry == null) {
                return Optional.empty();
            }
            if (!(entry instanceof ObjectNode)) {
                throw new IllegalArgumentException(client.name() + " server entry is not an object");
            }
            if (client.openCode()) {
                var command = entry.path("command");
                if (!(command instanceof ArrayNode array)
                        || array.isEmpty()
                        || !array.get(0).isTextual()) {
                    throw new IllegalArgumentException(client.name() + " server command is not a string array");
                }
                return Optional.of(new ServerSpec(
                        array.get(0).textValue(),
                        java.util.stream.IntStream.range(1, array.size())
                                .mapToObj(index -> text(array.get(index), client))
                                .toList(),
                        environment(client, entry)));
            }
            var command = text(entry.get("command"), client);
            var arguments = entry.path("args");
            if (!arguments.isArray()) {
                throw new IllegalArgumentException(client.name() + " server args is not an array");
            }
            return Optional.of(new ServerSpec(
                    command,
                    java.util.stream.IntStream.range(0, arguments.size())
                            .mapToObj(index -> text(arguments.get(index), client))
                            .toList(),
                    environment(client, entry)));
        } catch (IOException error) {
            throw new IllegalArgumentException(client.name() + " config is not valid JSON", error);
        }
    }

    private static String text(JsonNode node, Client client) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(client.name() + " server value is not a string");
        }
        return node.textValue();
    }
}
