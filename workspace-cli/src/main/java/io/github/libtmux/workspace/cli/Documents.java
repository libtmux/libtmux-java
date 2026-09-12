package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import picocli.CommandLine.ParseResult;

final class Documents {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private Documents() {}

    static ObjectNode read(Path source) throws IOException {
        try (MappingIterator<JsonNode> iterator = YAML.readerFor(JsonNode.class).readValues(source.toFile())) {
            List<JsonNode> documents = iterator.readAll();
            if (documents.size() != 1 || !documents.getFirst().isObject()) {
                throw new IllegalArgumentException("workspace must contain exactly one mapping document");
            }
            return (ObjectNode) documents.getFirst();
        }
    }

    static byte[] encode(JsonNode value, String format) throws IOException {
        return (format.equals("yaml") ? YAML : JSON)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(value);
    }

    static void write(Path destination, JsonNode value, String format, boolean replace) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("destination needs a parent directory");
        Path temporary = Files.createTempFile(parent, ".tmux-workspace-", ".tmp");
        try {
            Files.write(temporary, encode(value, format));
            if (replace) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(target, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void convert(Main.Context context, ParseResult args, Reporter report) throws IOException {
        String kind = args.commandSpec().name();
        Path source = Catalog.resolve(context, args.matchedPositionalValue(0, ""), kind);
        ObjectNode value = read(source);
        if (!kind.equals("convert")) value = importSource(value, kind);
        String defaultFormat =
                kind.equals("convert") && !Catalog.extension(source).equals("json") ? "json" : "yaml";
        String format = args.matchedOptionValue("--workspace-format", defaultFormat);
        String destination = args.matchedOptionValue("--save-to", "");
        if (destination.isEmpty() && report.machine()) {
            if (report.streaming()) {
                report.event(
                        "completed", JSON.createObjectNode().put("status", "ok").set("workspace", value));
            } else report.document(value);
            return;
        }
        Path target = destination.isEmpty()
                ? source.resolveSibling(stem(source) + "." + format)
                : context.directory().resolve(Catalog.expand(context, destination));
        if (!report.machine() && !Main.flag(args, "--yes"))
            confirm(context, "Save " + Catalog.mask(context, target) + "?");
        write(target, value, format, Main.flag(args, "--force"));
        ObjectNode saved = JSON.createObjectNode()
                .put("schema_version", 1)
                .put("command", kind)
                .put("status", "ok")
                .put("destination", Catalog.mask(context, target))
                .put("format", format);
        if (report.streaming()) report.event("completed", saved);
        else if (report.machine()) report.document(saved);
        else report.line("success", "Saved", Catalog.mask(context, target));
    }

    static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static void confirm(Main.Context context, String prompt) throws IOException {
        if (!Main.terminal()) throw Main.usage("confirmation requires a terminal; pass --yes");
        context.error().write((Reporter.safe(prompt) + " [y/N] ").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context.error().flush();
        int answer = context.input().read();
        if (answer != 'y' && answer != 'Y') throw new Main.Failure("cancelled", 1, "operation cancelled");
    }

    static ObjectNode importSource(ObjectNode source, String kind) {
        JsonNode input = kind.equals("teamocil") && source.has("session") ? source.path("session") : source;
        ObjectNode result = JSON.createObjectNode();
        result.set("session_name", input.path(input.has("project_name") ? "project_name" : "name"));
        copy(input, result, input.has("project_root") ? "project_root" : "root", "start_directory");
        ArrayNode windows = result.putArray("windows");
        if (kind.equals("tmuxinator")) {
            if (input.has("pre"))
                result.set(input.has("pre_window") ? "shell_command" : "shell_command_before", input.path("pre"));
            if (input.has("pre") && input.has("pre_window"))
                result.set("shell_command_before", input.path("pre_window"));
            if (input.has("rbenv")) {
                JsonNode previous = result.path("shell_command_before");
                ArrayNode before = JSON.createArrayNode();
                if (previous.isArray()) previous.forEach(before::add);
                else if (!previous.isMissingNode()) before.add(previous);
                before.add("rbenv shell " + input.path("rbenv").asText());
                result.set("shell_command_before", before);
            }
            copy(input, result, "socket_name", "socket_name");
            if (input.has("cli_args") || input.has("tmux_options"))
                result.put(
                        "config",
                        input.path(input.has("cli_args") ? "cli_args" : "tmux_options")
                                .asText()
                                .replace("-f", "")
                                .strip());
            for (JsonNode entry : input.path(input.has("tabs") ? "tabs" : "windows")) {
                entry.properties().forEach(field -> {
                    ObjectNode window = windows.addObject().put("window_name", field.getKey());
                    JsonNode config = field.getValue();
                    if (config.isObject()) {
                        copy(config, window, "panes", "panes");
                        copy(config, window, "pre", "shell_command_before");
                        copy(config, window, "root", "start_directory");
                        copy(config, window, "layout", "layout");
                    } else
                        window.set(
                                "panes",
                                config.isArray()
                                        ? config
                                        : JSON.createArrayNode().add(config));
                });
            }
        } else {
            for (JsonNode entry : input.path("windows")) {
                ObjectNode window = windows.addObject();
                copy(entry, window, "name", "window_name");
                copy(entry, window, "root", "start_directory");
                copy(entry, window, "clear", "clear");
                copy(entry, window, "layout", "layout");
                copy(entry.path("filters"), window, "before", "shell_command_before");
                copy(entry.path("filters"), window, "after", "shell_command_after");
                JsonNode panes =
                        entry.path(entry.has("splits") ? "splits" : "panes").deepCopy();
                if (!panes.isMissingNode()) window.set("panes", panes);
                for (JsonNode pane : panes) {
                    if (pane instanceof ObjectNode object) {
                        if (object.has("cmd")) object.set("shell_command", object.remove("cmd"));
                        object.remove("width");
                    }
                }
            }
        }
        return result;
    }

    private static void copy(JsonNode source, ObjectNode target, String from, String to) {
        if (source.has(from)) target.set(to, source.path(from));
    }
}
