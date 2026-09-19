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
import org.jspecify.annotations.Nullable;

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
        List<Window> windows,
        List<Warning> warnings,
        @Nullable ObjectNode extension) {
    enum Readiness {
        AUTO,
        ALWAYS,
        NEVER
    }

    /** Something the document asks for that loading will not do, said once and carried on. */
    record Warning(String code, String message) {}

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
        return parse(context, source, Documents.read(source), rename, true);
    }

    static void validateImported(Main.Context context, Path source, ObjectNode root) {
        parse(context, source, root, "", false);
    }

    /**
     * The rule both ends of a round trip apply, so a capture never writes a name a load refuses.
     *
     * <p>tmux's answer to {@code :} or {@code .} in a session name changes across the supported
     * range — rewritten to {@code _} through 3.6, refused outright on 3.7, kept from 3.7a on but only
     * an explicit {@code name:} terminator then selects it, so a bare {@code -t name} still misreads
     * the delimiter as a window separator. No spelling survives the whole range, so both ends refuse
     * the name outright rather than build something the other cannot read back. {@code remedy} says
     * what the caller's side of the round trip can do about it.
     */
    static void requireAddressableName(String name, String remedy) {
        if (name.isEmpty() || name.indexOf('\0') >= 0) throw invalid("session_name must be nonempty text without NUL");
        if (name.indexOf(':') >= 0)
            throw invalid("session_name must not contain ':': tmux uses it as the session:window separator" + remedy);
        if (name.indexOf('.') >= 0)
            throw invalid("session_name must not contain '.': tmux uses it as the window.pane separator" + remedy);
    }

    private static WorkspacePlan parse(
            Main.Context context, Path source, ObjectNode root, String rename, boolean checkDirectories) {
        String name = rename.isEmpty() ? text(context, root.path("session_name"), "session_name") : rename;
        requireAddressableName(name, "; load with -s to choose another name");
        Path parent = source.getParent();
        if (parent == null) throw invalid("workspace source has no parent");
        List<Warning> warnings = new ArrayList<>();
        Path directory = directory(context, root, context.directory(), parent, checkDirectories, warnings);
        Path directoryBase = root.hasNonNull("start_directory") ? directory : parent;
        Path scriptDirectory =
                root.hasNonNull("start_directory") && Files.isDirectory(directory) ? directory : context.directory();
        String script = optionalText(context, root.path("before_script"), "before_script", "");
        List<String> beforeScript = script.isEmpty() ? List.of() : Children.words(script);
        if (PythonExtensions.required(context, source, root)) {
            root.put("session_name", name);
            return new WorkspacePlan(
                    source,
                    name,
                    directory,
                    scriptDirectory,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    beforeScript,
                    Readiness.NEVER,
                    List.of(),
                    List.copyOf(warnings),
                    root);
        }
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
        Map<String, String> environment = mapping(context, root.path("environment"), true);
        Map<String, String> options = mapping(context, root.path("options"), false);
        Map<String, String> globalOptions = mapping(context, root.path("global_options"), false);
        Readiness readiness = readiness(root.path("workspace_builder_options"), warnings);
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
            Path windowDirectory = directory(context, node, directory, directoryBase, checkDirectories, warnings);
            Path windowBase = node.hasNonNull("start_directory") ? windowDirectory : directoryBase;
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
                Path paneDirectory = directory(context, pane, windowDirectory, windowBase, checkDirectories, warnings);
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
                        focus(pane.path("focus")),
                        commands(context, raw, paneSuppress, pane)));
            }
            windows.add(new Window(
                    optionalText(context, node.path("window_name"), "window_name", ""),
                    index,
                    layout,
                    focus(node.path("focus")),
                    mapping(context, node.path("options"), false),
                    mapping(context, node.path("options_after"), false),
                    List.copyOf(panes)));
        }
        return new WorkspacePlan(
                source,
                name,
                directory,
                scriptDirectory,
                environment,
                options,
                globalOptions,
                beforeScript,
                readiness,
                List.copyOf(windows),
                List.copyOf(warnings),
                null);
    }

    /** An unrecognised builder option is said and ignored; it never refuses the document. */
    private static Readiness readiness(JsonNode catalog, List<Warning> warnings) {
        if (catalog.isMissingNode() || catalog.isNull()) return Readiness.AUTO;
        if (!catalog.isObject()) throw invalid("workspace_builder_options must be a mapping");
        catalog.fieldNames().forEachRemaining(name -> {
            if (!name.equals("pane_readiness") && !name.startsWith("x-"))
                warnings.add(new Warning(
                        "unsupported_builder_option",
                        "workspace_builder_options." + name + " is not implemented by native loading; ignored"));
        });
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

    private static List<Command> commands(Main.Context context, List<JsonNode> raw, boolean suppress, JsonNode pane) {
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
            String value = Catalog.expand(context, text.asText());
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

    /**
     * {@code focus} alone accepts the quoted string {@code tmuxp freeze} writes, in addition to a
     * YAML boolean; every other boolean field keeps the strict check.
     */
    private static boolean focus(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return false;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isTextual()
                && (value.asText().equals("true") || value.asText().equals("false")))
            return Boolean.parseBoolean(value.asText());
        throw invalid("focus must be a boolean");
    }

    private static int integer(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0)
            throw invalid(field + " must be a nonnegative integer");
        return value.asInt();
    }

    /**
     * The effective start_directory at this node: {@code inherited} when the node names none, or
     * {@code relativeBase}-relative (or absolute) otherwise. {@code relativeBase} is the nearest
     * ancestor's own start_directory when one declared it, and the workspace document's directory
     * otherwise.
     *
     * <p>A missing target directory is not refused: tmux itself falls back to {@code $HOME} for a
     * {@code -c} it cannot use, so this records a warning instead and lets tmux do the same.
     */
    private static Path directory(
            Main.Context context,
            JsonNode node,
            Path inherited,
            Path relativeBase,
            boolean checkDirectories,
            List<Warning> warnings) {
        JsonNode value = node.path("start_directory");
        if (value.isMissingNode() || value.isNull()) return inherited;
        Path resolved =
                relativeBase.resolve(text(context, value, "start_directory")).normalize();
        if (checkDirectories && !Files.isDirectory(resolved))
            warnings.add(new Warning(
                    "start_directory_missing",
                    "start_directory is not a directory, tmux will fall back to $HOME: " + resolved));
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

    /** A key starting with {@code x-}, at any level, is inert rather than refused. */
    private static void keys(JsonNode node, Set<String> allowed, String field) {
        if (!node.isObject()) throw invalid(field + " must be a mapping");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name) && !name.startsWith("x-"))
                throw unsupportedKey(field + "." + name
                        + " is not supported by native loading; prefix a custom key with 'x-' to pass it through");
        });
    }

    private static Main.Failure invalid(String message) {
        return new Main.Failure(Machine.Code.INVALID_WORKSPACE, 1, message);
    }

    private static Main.Failure unsupportedKey(String message) {
        return new Main.Failure(Machine.Code.UNSUPPORTED_KEY, 1, message);
    }
}
