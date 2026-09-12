package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.Layouts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record WorkspacePlan(
        Path source,
        String name,
        Path directory,
        Path scriptDirectory,
        Map<String, String> environment,
        Map<String, String> options,
        Map<String, String> globalOptions,
        List<String> beforeScript,
        Readiness readiness,
        List<Window> windows) {
    enum Readiness {
        AUTO,
        ALWAYS,
        NEVER
    }

    record Command(String text, boolean enter, Duration before, Duration after) {}

    record Pane(Path directory, Map<String, String> environment, String shell, boolean focus, List<Command> commands) {}

    record Window(
            String name,
            int index,
            String layout,
            boolean focus,
            Map<String, String> options,
            Map<String, String> optionsAfter,
            List<Pane> panes) {}

    static WorkspacePlan read(Main.Context context, Path source, String rename) throws java.io.IOException {
        ObjectNode root = Documents.read(source);
        keys(
                root,
                Set.of(
                        "session_name",
                        "windows",
                        "start_directory",
                        "environment",
                        "options",
                        "global_options",
                        "before_script",
                        "shell_command_before",
                        "suppress_history",
                        "workspace_builder_options",
                        "plugins",
                        "workspace_builder",
                        "workspace_builder_paths"),
                "workspace");
        if (root.has("plugins") || root.has("workspace_builder") || root.has("workspace_builder_paths")) {
            throw new Main.Failure("python_runtime", 1, "Python workspace extensions require the tmuxp 1.74.0 bridge");
        }
        String name = rename.isEmpty() ? text(context, root.path("session_name"), "session_name") : rename;
        if (name.isEmpty() || name.indexOf('\0') >= 0) throw invalid("session_name must be nonempty text without NUL");
        Path parent = source.getParent();
        if (parent == null) throw invalid("workspace source has no parent");
        Path directory = directory(context, root, parent);
        Path scriptDirectory = root.hasNonNull("start_directory") ? directory : context.directory();
        Map<String, String> environment = mapping(context, root.path("environment"), true);
        Map<String, String> options = mapping(context, root.path("options"), false);
        Map<String, String> globalOptions = mapping(context, root.path("global_options"), false);
        Readiness readiness = readiness(root.path("workspace_builder_options"));
        JsonNode windowNodes = root.path("windows");
        if (!windowNodes.isArray() || windowNodes.isEmpty()) throw invalid("windows must be a nonempty array");
        List<Window> windows = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        for (JsonNode node : windowNodes) {
            keys(
                    node,
                    Set.of(
                            "window_name",
                            "window_index",
                            "panes",
                            "layout",
                            "focus",
                            "start_directory",
                            "environment",
                            "options",
                            "options_after",
                            "shell_command_before",
                            "suppress_history",
                            "window_shell"),
                    "window");
            int index = node.has("window_index") ? integer(node.path("window_index"), "window_index") : -1;
            if (index >= 0 && !indexes.add(index)) throw invalid("duplicate window_index " + index);
            String layout = optionalText(context, node.path("layout"), "layout", "");
            if (!layout.isEmpty()) Layouts.require(layout);
            Path windowDirectory = directory(context, node, directory);
            Map<String, String> windowEnvironment = mapping(context, node.path("environment"), true);
            String shell = optionalText(context, node.path("window_shell"), "window_shell", "");
            boolean suppress = bool(node.path("suppress_history"), bool(root.path("suppress_history"), true));
            JsonNode paneNodes = node.path("panes");
            if (!paneNodes.isMissingNode() && !paneNodes.isArray()) throw invalid("panes must be an array");
            if (paneNodes.isEmpty())
                paneNodes = Documents.JSON.createArrayNode().addNull();
            List<Pane> panes = new ArrayList<>();
            for (JsonNode pane : paneNodes) {
                if (pane.isObject())
                    keys(
                            pane,
                            Set.of(
                                    "shell_command",
                                    "shell_command_before",
                                    "start_directory",
                                    "environment",
                                    "focus",
                                    "suppress_history",
                                    "enter",
                                    "sleep_before",
                                    "sleep_after",
                                    "shell",
                                    "pane_shell"),
                            "pane");
                Path paneDirectory = directory(context, pane, windowDirectory);
                Map<String, String> paneEnvironment =
                        pane.has("environment") ? mapping(context, pane.path("environment"), true) : windowEnvironment;
                boolean paneSuppress = bool(pane.path("suppress_history"), suppress);
                if (pane.hasNonNull("shell") && pane.hasNonNull("pane_shell"))
                    throw invalid("pane.shell and pane.pane_shell cannot both be set");
                List<JsonNode> raw = new ArrayList<>();
                append(raw, root.path("shell_command_before"));
                append(raw, node.path("shell_command_before"));
                append(raw, pane.path("shell_command_before"));
                JsonNode own = pane.isObject() ? pane.path("shell_command") : pane;
                if (!(own.isTextual() && Set.of("blank", "pane", "empty").contains(own.asText()))
                        && !(own.isArray()
                                && own.size() == 1
                                && own.path(0).isTextual()
                                && Set.of("blank", "pane", "empty")
                                        .contains(own.path(0).asText()))) append(raw, own);
                panes.add(new Pane(
                        paneDirectory,
                        paneEnvironment,
                        optionalText(
                                context,
                                pane.path("shell"),
                                "shell",
                                optionalText(context, pane.path("pane_shell"), "pane_shell", shell)),
                        bool(pane.path("focus"), false),
                        commands(raw, paneSuppress, pane)));
            }
            windows.add(new Window(
                    optionalText(context, node.path("window_name"), "window_name", ""),
                    index,
                    layout,
                    bool(node.path("focus"), false),
                    mapping(context, node.path("options"), false),
                    mapping(context, node.path("options_after"), false),
                    List.copyOf(panes)));
        }
        String script = optionalText(context, root.path("before_script"), "before_script", "");
        return new WorkspacePlan(
                source,
                name,
                directory,
                scriptDirectory,
                environment,
                options,
                globalOptions,
                script.isEmpty() ? List.of() : Children.words(script),
                readiness,
                List.copyOf(windows));
    }

    private static Readiness readiness(JsonNode catalog) {
        if (catalog.isMissingNode() || catalog.isNull()) return Readiness.AUTO;
        keys(catalog, Set.of("pane_readiness"), "workspace_builder_options");
        JsonNode value = catalog.path("pane_readiness");
        if (value.isMissingNode() || value.isNull()) return Readiness.AUTO;
        return switch (value.asText().strip().toLowerCase(java.util.Locale.ROOT)) {
            case "auto" -> Readiness.AUTO;
            case "always", "true", "on", "yes", "1" -> Readiness.ALWAYS;
            case "never", "false", "off", "no", "0" -> Readiness.NEVER;
            default ->
                throw invalid("workspace_builder_options.pane_readiness must be auto, always or never (or a boolean)");
        };
    }

    private static void append(List<JsonNode> target, JsonNode value) {
        if (value.isArray()) value.forEach(target::add);
        else if (!value.isMissingNode() && !value.isNull()) target.add(value);
    }

    private static List<Command> commands(List<JsonNode> raw, boolean suppress, JsonNode pane) {
        var result = new ArrayList<Command>();
        boolean enter = bool(pane.path("enter"), true);
        Duration before = delay(pane.path("sleep_before"));
        Duration after = delay(pane.path("sleep_after"));
        for (JsonNode command : raw) {
            if (command.isNull()) continue;
            JsonNode text = command;
            if (command.isObject()) {
                keys(command, Set.of("cmd", "enter", "sleep_before", "sleep_after"), "command");
                text = command.path("cmd");
                if (command.has("enter")) enter = bool(command.path("enter"), false);
                if (command.has("sleep_before")) before = delay(command.path("sleep_before"));
                if (command.has("sleep_after")) after = delay(command.path("sleep_after"));
            }
            if (!text.isTextual() || text.asText().indexOf('\0') >= 0)
                throw invalid("command must be text without NUL");
            String value = text.asText();
            result.add(new Command(suppress ? " " + value : value, enter, before, after));
        }
        return List.copyOf(result);
    }

    private static Duration delay(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return Duration.ZERO;
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0)
            throw invalid("command sleep must be a nonnegative finite number");
        return Duration.ofNanos((long) (value.asDouble() * 1_000_000_000));
    }

    private static boolean bool(JsonNode value, boolean fallback) {
        if (value.isMissingNode() || value.isNull()) return fallback;
        if (!value.isBoolean()) throw invalid("expected a boolean");
        return value.asBoolean();
    }

    private static int integer(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
            throw invalid(field + " must be a nonnegative integer");
        return value.asInt();
    }

    private static Path directory(Main.Context context, JsonNode node, Path fallback) {
        JsonNode value = node.path("start_directory");
        if (value.isMissingNode() || value.isNull()) return fallback;
        Path resolved =
                fallback.resolve(text(context, value, "start_directory")).normalize();
        if (!Files.isDirectory(resolved)) throw invalid("start_directory is not a directory: " + resolved);
        return resolved;
    }

    private static String optionalText(Main.Context context, JsonNode value, String field, String fallback) {
        return value.isMissingNode() || value.isNull() ? fallback : text(context, value, field);
    }

    private static String text(Main.Context context, JsonNode value, String field) {
        if (!value.isTextual()) throw invalid(field + " must be text");
        String text = Catalog.expand(context, value.asText());
        if (text.indexOf('\0') >= 0) throw invalid(field + " cannot contain NUL");
        return text;
    }

    private static Map<String, String> mapping(Main.Context context, JsonNode node, boolean environment) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) throw invalid("options and environment must be mappings");
        Map<String, String> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.isEmpty() || key.indexOf('\0') >= 0 || (environment && key.contains("=")))
                throw invalid("invalid environment or option key");
            if (value.isContainerNode() || value.isNull())
                throw invalid("environment and option values must be scalar");
            String text = !environment && value.isBoolean()
                    ? value.asBoolean() ? "on" : "off"
                    : Catalog.expand(context, value.asText());
            if (text.indexOf('\0') >= 0) throw invalid("environment and option values cannot contain NUL");
            result.put(key, text);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void keys(JsonNode node, Set<String> allowed, String field) {
        if (!node.isObject()) throw invalid(field + " must be a mapping");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid(field + "." + name + " is not supported by native loading");
        });
    }

    private static Main.Failure invalid(String message) {
        return new Main.Failure("invalid_config", 1, message);
    }
}
