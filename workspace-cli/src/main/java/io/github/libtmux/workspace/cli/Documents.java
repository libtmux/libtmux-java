package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.core.JsonFactory;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import picocli.CommandLine.ParseResult;

final class Documents {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private Documents() {}

    static ObjectNode read(Path source) throws IOException {
        if (!Catalog.extension(source).equals("json")) return readYaml(source);
        try (MappingIterator<JsonNode> iterator = JSON.readerFor(JsonNode.class).readValues(source.toFile())) {
            List<JsonNode> documents = iterator.readAll();
            if (documents.size() != 1 || !documents.getFirst().isObject()) {
                throw new IllegalArgumentException("workspace must contain exactly one mapping document");
            }
            return (ObjectNode) documents.getFirst();
        }
    }

    private static ObjectNode readYaml(Path source) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setWarnOnDuplicateKeys(false);
        options.setNestingDepthLimit(100);
        try (var input = Files.newBufferedReader(source)) {
            var documents =
                    new Yaml(new WorkspaceConstructor(options)).loadAll(input).iterator();
            if (!documents.hasNext()) throw new IllegalArgumentException("workspace must contain one mapping document");
            Object value = documents.next();
            if (documents.hasNext()) throw new IllegalArgumentException("workspace must contain one mapping document");
            JsonNode tree = new YamlTree().convert(value);
            if (!(tree instanceof ObjectNode mapping))
                throw new IllegalArgumentException("workspace must contain one mapping document");
            return mapping;
        } catch (YAMLException failure) {
            throw new IllegalArgumentException("invalid YAML: " + failure.getMessage(), failure);
        }
    }

    private static final class WorkspaceConstructor extends SafeConstructor {
        WorkspaceConstructor(LoaderOptions options) {
            super(options);
            yamlConstructors.put(Tag.TIMESTAMP, new ConstructYamlStr());
        }
    }

    private static final class YamlTree {
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private int remaining = 100_000;

        JsonNode convert(@Nullable Object value) {
            if (--remaining < 0) throw new IllegalArgumentException("expanded YAML exceeds 100000 values");
            if (value instanceof Double number && !Double.isFinite(number))
                throw new IllegalArgumentException("YAML numbers must be finite");
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
                return JSON.valueToTree(value);
            if (active.size() >= 100)
                throw new IllegalArgumentException("expanded YAML is nested more than 100 levels");
            if (!active.add(value)) throw new IllegalArgumentException("YAML aliases cannot form cycles");
            try {
                if (value instanceof Map<?, ?> mapping) {
                    ObjectNode result = JSON.createObjectNode();
                    for (var entry : mapping.entrySet()) {
                        if (!(entry.getKey() instanceof String key))
                            throw new IllegalArgumentException("YAML mapping keys must be strings");
                        result.set(key, convert(entry.getValue()));
                    }
                    return result;
                }
                if (value instanceof List<?> sequence) {
                    ArrayNode result = JSON.createArrayNode();
                    for (Object item : sequence) result.add(convert(item));
                    return result;
                }
                throw new IllegalArgumentException("YAML value cannot be represented in JSON");
            } finally {
                active.remove(value);
            }
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
                try {
                    Files.createLink(target, temporary);
                } catch (UnsupportedOperationException withoutLinks) {
                    // A link refuses an existing destination without a window between the two;
                    // a store that has no links leaves that to the move itself.
                    Files.move(temporary, target);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void convert(Main.Context context, ParseResult args, Reporter report) throws IOException {
        String kind = args.commandSpec().name();
        Path source = Catalog.resolve(context, args.matchedPositionalValue(0, ""), kind);
        ObjectNode value = read(source);
        if (!kind.equals("convert")) {
            value = importSource(context, source, value, kind);
            WorkspacePlan.validateImported(context, source, value);
        }
        String defaultFormat =
                kind.equals("convert") && !Catalog.extension(source).equals("json") ? "json" : "yaml";
        String format = args.matchedOptionValue("--workspace-format", defaultFormat);
        String destination = args.matchedOptionValue("--save-to", "");
        if (destination.isEmpty() && report.machine()) {
            if (report.streaming()) {
                report.event(
                        Machine.Event.COMPLETED,
                        JSON.createObjectNode().put("status", "ok").set("workspace", value));
            } else report.document(value);
            return;
        }
        Path target = destination.isEmpty()
                ? source.resolveSibling(stem(source) + "." + format)
                : context.directory().resolve(Catalog.expand(context, destination));
        if (!report.machine()
                && !Main.flag(args, "--yes")
                && !confirm(context, "Save " + Catalog.mask(context, target) + "?")) {
            report.line("subject", "Not saved", Catalog.mask(context, target));
            return;
        }
        try {
            write(target, value, format, Main.flag(args, "--force"));
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            throw new Main.Failure(
                    Machine.Code.DESTINATION_EXISTS, 1, "destination already exists: " + Catalog.mask(context, target));
        }
        ObjectNode saved = JSON.createObjectNode()
                .put("schema_version", Machine.SCHEMA_VERSION)
                .put("command", kind)
                .put("status", "ok")
                .put("destination", Catalog.mask(context, target))
                .put("format", format);
        if (report.streaming()) report.event(Machine.Event.COMPLETED, saved);
        else if (report.machine()) report.document(saved);
        else report.line("success", "Saved", Catalog.mask(context, target));
    }

    static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Asks, and says whether the answer was yes.
     *
     * <p>Saying no is an answer, not a failure: the caller does nothing and reports that. Only
     * being unable to ask at all refuses, because then the request cannot be carried out as given.
     */
    static boolean confirm(Main.Context context, String prompt) throws IOException {
        if (!Main.terminal()) throw Main.usage("confirmation requires a terminal; pass --yes");
        context.error().write((Reporter.safe(prompt) + " [y/N] ").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context.error().flush();
        String line = promptLine(context);
        return line != null && !line.isEmpty() && (line.charAt(0) == 'y' || line.charAt(0) == 'Y');
    }

    /**
     * One line typed in answer to a prompt, or null at end of input.
     *
     * <p>The one place a prompt reads stdin, so two prompts asked in the same process cannot
     * disagree about whether a typed line's unread bytes belong to the next question.
     */
    static @Nullable String promptLine(Main.Context context) throws IOException {
        return new java.io.BufferedReader(
                        new java.io.InputStreamReader(context.input(), java.nio.charset.StandardCharsets.UTF_8))
                .readLine();
    }

    private static ObjectNode importSource(Main.Context context, Path source, ObjectNode document, String kind) {
        boolean tmuxinator = kind.equals("tmuxinator");
        if (tmuxinator && document.toString().contains("<%"))
            throw importError("tmuxinator ERB templates are unsupported; expand them before import");
        JsonNode input = document;
        if (!tmuxinator && document.hasNonNull("session")) {
            importKeys(document, Set.of("session"), "document");
            input = document.path("session");
        }
        importKeys(
                input,
                tmuxinator
                        ? Set.of(
                                "name",
                                "project_name",
                                "root",
                                "project_root",
                                "windows",
                                "tabs",
                                "pre_window",
                                "pre_tab")
                        : Set.of("name", "root", "windows"),
                kind);
        JsonNode name = tmuxinator ? alias(input, "project_name", "name") : input.path("name");
        ObjectNode result =
                JSON.createObjectNode().put("session_name", absent(name) ? stem(source) : importText(name, "name"));
        Path directory = importDirectory(
                context, tmuxinator ? alias(input, "project_root", "root") : input.path("root"), context.directory());
        result.put("start_directory", directory.toString());
        if (tmuxinator) {
            String before = commandGroup(alias(input, "pre_tab", "pre_window"), "pre_window", "; ");
            if (!before.isEmpty()) result.put("shell_command_before", before);
        }
        JsonNode entries = tmuxinator ? alias(input, "tabs", "windows") : input.path("windows");
        if (!entries.isArray() || entries.isEmpty()) throw importError("windows must be a nonempty array");
        ArrayNode windows = result.putArray("windows");
        for (JsonNode entry : entries) {
            ObjectNode window = tmuxinator
                    ? tmuxinatorWindow(context, directory, entry)
                    : teamocilWindow(context, directory, entry);
            windows.add(window);
        }
        firstFocus(windows);
        return result;
    }

    private static ObjectNode tmuxinatorWindow(Main.Context context, Path directory, JsonNode entry) {
        if (!entry.isObject() || entry.size() != 1)
            throw importError("each tmuxinator window must have exactly one name");
        var field = entry.properties().iterator().next();
        JsonNode input = field.getValue();
        ObjectNode window = JSON.createObjectNode().put("window_name", field.getKey());
        ArrayNode panes = window.putArray("panes");
        if (input.isObject()) {
            importKeys(input, Set.of("root", "layout", "pre", "panes", "synchronize"), "tmuxinator window");
            window.put(
                    "start_directory",
                    importDirectory(context, input.path("root"), directory).toString());
            copy(input, window, "layout", "layout");
            String before = commandGroup(input.path("pre"), "window.pre", " && ");
            JsonNode entries = input.path("panes");
            if (!absent(entries) && !entries.isArray()) throw importError("window.panes must be an array");
            if (!before.isEmpty()) {
                if (entries.isEmpty()) throw importError("window.pre requires explicit nonempty panes");
                window.put("shell_command_before", before);
            }
            for (JsonNode pane : entries) {
                if (pane.isObject())
                    throw importError(
                            "named tmuxinator panes require pane titles, which native loading does not support");
                panes.addObject().set("shell_command", literalCommands(importCommands(pane, "pane")));
            }
            JsonNode synchronize = input.path("synchronize");
            if (!absent(synchronize)
                    && !synchronize.equals(JSON.getNodeFactory().booleanNode(false))) {
                if (!synchronize.isTextual() || !synchronize.asText().equals("after"))
                    throw importError("synchronize before pane commands is not supported; use synchronize: after");
                window.putObject("options_after").put("synchronize-panes", true);
            }
        } else {
            panes.addObject().set("shell_command", literalCommands(importCommands(input, "window commands")));
        }
        if (panes.isEmpty()) panes.addObject();
        firstFocus(panes);
        return window;
    }

    private static ObjectNode teamocilWindow(Main.Context context, Path directory, JsonNode input) {
        importKeys(input, Set.of("name", "root", "layout", "focus", "options", "panes", "splits"), "teamocil window");
        ObjectNode window = JSON.createObjectNode();
        copy(input, window, "name", "window_name");
        copy(input, window, "layout", "layout");
        copy(input, window, "focus", "focus");
        window.put(
                "start_directory",
                importDirectory(context, input.path("root"), directory).toString());
        JsonNode options = input.path("options");
        if (!absent(options)) {
            if (!options.isObject()) throw importError("window.options must be a mapping");
            JsonNode synchronize = options.path("synchronize-panes");
            if (!absent(synchronize)
                    && !(synchronize.isBoolean() && !synchronize.asBoolean())
                    && !(synchronize.isTextual() && Set.of("off", "0").contains(synchronize.asText())))
                throw importError("Teamocil synchronize-panes before pane commands is not supported");
            window.set("options", options);
        }
        JsonNode entries = alias(input, "panes", "splits");
        if (!absent(entries) && !entries.isArray()) throw importError("window.panes must be an array");
        ArrayNode panes = window.putArray("panes");
        for (JsonNode entry : entries) {
            ObjectNode pane = panes.addObject();
            JsonNode commands = entry;
            if (entry.isObject()) {
                importKeys(entry, Set.of("commands", "cmd", "focus"), "teamocil pane");
                commands = alias(entry, "commands", "cmd");
                copy(entry, pane, "focus", "focus");
            }
            String command = commandGroup(commands, "pane commands", "; ");
            if (!command.isEmpty())
                pane.set("shell_command", literalCommands(JSON.getNodeFactory().textNode(command)));
        }
        if (panes.isEmpty()) panes.addObject();
        firstFocus(panes);
        return window;
    }

    private static void firstFocus(ArrayNode items) {
        ObjectNode selected = (ObjectNode) items.get(0);
        boolean found = false;
        for (JsonNode item : items) {
            JsonNode focus = item.path("focus");
            if (!absent(focus) && !focus.isBoolean()) throw importError("focus must be a boolean");
            if (!found && focus.asBoolean()) {
                selected = (ObjectNode) item;
                found = true;
            }
            ((ObjectNode) item).put("focus", false);
        }
        selected.put("focus", true);
    }

    private static Path importDirectory(Main.Context context, JsonNode value, Path fallback) {
        if (absent(value)) return fallback.toAbsolutePath().normalize();
        return fallback.resolve(Catalog.expand(context, importText(value, "root")))
                .toAbsolutePath()
                .normalize();
    }

    private static ArrayNode importCommands(JsonNode value, String field) {
        ArrayNode commands = JSON.createArrayNode();
        if (absent(value)) return commands;
        if (value.isArray()) {
            for (JsonNode command : value) if (!command.isNull()) commands.add(importText(command, field));
        } else commands.add(importText(value, field));
        return commands;
    }

    private static JsonNode literalCommands(JsonNode commands) {
        JsonNode single = commands.isArray() && commands.size() == 1 ? commands.path(0) : commands;
        // Imported shell commands must not become native blank-pane shorthand.
        return single.isTextual() && Set.of("blank", "empty", "pane").contains(single.asText())
                ? JSON.createObjectNode().put("cmd", single.asText())
                : commands;
    }

    private static String commandGroup(JsonNode value, String field, String separator) {
        var commands = new java.util.ArrayList<String>();
        for (JsonNode command : importCommands(value, field)) commands.add(command.asText());
        return String.join(separator, commands);
    }

    private static String importText(JsonNode value, String field) {
        if (!value.isTextual() || value.asText().indexOf('\0') >= 0)
            throw importError(field + " must be text without NUL");
        return value.asText();
    }

    private static boolean absent(JsonNode value) {
        return value.isMissingNode() || value.isNull();
    }

    private static JsonNode alias(JsonNode input, String first, String second) {
        if (input.hasNonNull(first) && input.hasNonNull(second))
            throw importError(first + " and " + second + " cannot both be set");
        return input.hasNonNull(first) ? input.path(first) : input.path(second);
    }

    private static void importKeys(JsonNode input, Set<String> allowed, String scope) {
        if (!input.isObject()) throw importError(scope + " must be a mapping");
        input.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key))
                throw new Main.Failure(
                        Machine.Code.UNSUPPORTED_KEY, 1, scope + "." + key + " is not supported by native import");
        });
    }

    private static Main.Failure importError(String message) {
        return new Main.Failure(Machine.Code.INVALID_WORKSPACE, 1, message);
    }

    private static void copy(JsonNode source, ObjectNode target, String from, String to) {
        if (source.hasNonNull(from)) target.set(to, source.path(from));
    }
}
