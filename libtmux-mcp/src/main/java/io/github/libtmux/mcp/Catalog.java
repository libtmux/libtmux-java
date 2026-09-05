package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.Argument.boundedObjects;
import static io.github.libtmux.mcp.Argument.boundedRequired;
import static io.github.libtmux.mcp.Argument.boundedStrings;
import static io.github.libtmux.mcp.Argument.flag;
import static io.github.libtmux.mcp.Argument.number;
import static io.github.libtmux.mcp.Argument.objects;
import static io.github.libtmux.mcp.Argument.optional;
import static io.github.libtmux.mcp.Argument.paneId;
import static io.github.libtmux.mcp.Argument.required;
import static io.github.libtmux.mcp.Argument.requiredNumber;
import static io.github.libtmux.mcp.Argument.seconds;
import static io.github.libtmux.mcp.Argument.strings;
import static io.github.libtmux.mcp.OutputSchema.ValueType.ARRAY;
import static io.github.libtmux.mcp.OutputSchema.ValueType.BOOLEAN;
import static io.github.libtmux.mcp.OutputSchema.ValueType.INTEGER;
import static io.github.libtmux.mcp.OutputSchema.ValueType.OBJECT;
import static io.github.libtmux.mcp.OutputSchema.ValueType.STRING;
import static io.github.libtmux.mcp.ToolSpec.InputSink.REGEX;
import static io.github.libtmux.mcp.ToolSpec.InputSink.SHELL_COMMAND;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_FORMAT;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_LOOKUP;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_STATE;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.CONFIGURED_COMMAND;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.PROCESS_ENVIRONMENT;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.TERMINAL_CONTENT;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.TMUX_METADATA;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.CONFIGURED_PROCESS;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.NONE;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.PANE_COMMAND;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.PANE_INPUT;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.CHANGE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.DELETE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.OBSERVE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.EXECUTE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.INSPECT;
import static io.github.libtmux.mcp.ToolSpec.Toolset.MANAGE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.TEARDOWN;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Every public structured tool, declared once in deterministic registration order. */
final class Catalog {

    private static final OutputSchema SESSION_OUTPUT =
            shape(field("id", STRING), field("name", STRING), field("attached", BOOLEAN), field("windows", INTEGER));
    private static final OutputSchema WINDOW_OUTPUT = shape(
            field("id", STRING),
            field("index", INTEGER),
            field("name", STRING),
            field("session_id", STRING),
            field("active", BOOLEAN),
            field("panes", INTEGER),
            field("size", STRING));
    private static final OutputSchema PANE_OUTPUT = shape(
            field("id", STRING),
            field("index", INTEGER),
            field("window_id", STRING),
            field("session_id", STRING),
            field("active", BOOLEAN),
            field("command", STRING),
            field("path", STRING),
            field("title", STRING),
            field("size", STRING));

    private static final List<ToolSpec> TOOLS = build();

    private Catalog() {}

    static List<ToolSpec> tools() {
        return TOOLS;
    }

    static ToolSpec named(String name) {
        return TOOLS.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown catalog tool '" + name + "'"));
    }

    static void validate(List<ToolSpec> tools) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, ToolSpec> byName = new LinkedHashMap<>();
        for (ToolSpec tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("duplicate tool '" + tool.name() + "'");
            }
            byName.put(tool.name(), tool);
        }
        for (ToolSpec tool : tools) {
            validateSchema(tool);
            validateReach(tool);
            if (tool.outputClasses().isEmpty()) {
                throw new IllegalArgumentException(tool.name() + " has no output class");
            }
            if (!tool.description().startsWith(tool.controlledOpener() + " ")) {
                throw new IllegalArgumentException(tool.name() + " does not begin with its controlled opener");
            }
            if (tool.processReach() == ToolSpec.ProcessReach.HOST_COMMAND) {
                throw new IllegalArgumentException(tool.name() + " exposes prohibited host-command reach");
            }
            Set<String> formatInputs = tool.inputSinks().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(TMUX_FORMAT))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (!formatInputs.equals(tool.inputLiteralization().keySet())
                    || tool.inputLiteralization().values().stream()
                            .anyMatch(strategy -> !Set.of("double-hash-once", "validated-variable-name")
                                    .contains(strategy))) {
                throw new IllegalArgumentException(tool.name() + " has inconsistent tmux-format controls");
            }
            for (Map.Entry<String, String> control : tool.inputLiteralization().entrySet()) {
                ToolSpec.InputSink classified =
                        control.getValue().equals("double-hash-once") ? TMUX_STATE : TMUX_LOOKUP;
                if (!Objects.requireNonNull(tool.inputSinks().get(control.getKey()), control.getKey())
                        .contains(classified)) {
                    throw new IllegalArgumentException(
                            tool.name() + " understates the sink for '" + control.getKey() + "'");
                }
            }
            if (tool.amplifiesFutureInput() != tool.name().equals("set_synchronize_panes")) {
                throw new IllegalArgumentException(tool.name() + " has incorrect future-input amplification");
            }
            if (!tool.annotations().equals(conservativeAnnotations())) {
                throw new IllegalArgumentException(
                        tool.name() + " is not conservative under unknown configuration provenance");
            }
            for (String nested : tool.nestedAuthority()) {
                if (nested.equals(tool.name()) || !names.contains(nested)) {
                    throw new IllegalArgumentException(tool.name() + " has invalid nested authority '" + nested + "'");
                }
            }
            if (!tool.nestedAuthority().isEmpty()) {
                ToolSpec derived = tool.withNestedAuthority(tool.nestedAuthority(), byName);
                if (!tool.effects().equals(derived.effects())
                        || !tool.outputClasses().equals(derived.outputClasses())
                        || tool.mayExposeSecrets() != derived.mayExposeSecrets()
                        || tool.mayReturnUntrustedContent() != derived.mayReturnUntrustedContent()) {
                    throw new IllegalArgumentException(tool.name() + " understates its nested capability union");
                }
            }
        }
    }

    private static List<ToolSpec> build() {
        List<ToolSpec> tools = new ArrayList<>();
        inspect(tools);
        manage(tools);
        execute(tools);
        teardown(tools);
        List<ToolSpec> built = List.copyOf(tools);
        validate(built);
        return built;
    }

    private static void inspect(List<ToolSpec> tools) {
        tools.add(tool(
                "list_sessions",
                "List sessions",
                "Lists sessions on the pinned tmux server.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TMUX_METADATA),
                true,
                true,
                List.of(),
                Map.of(),
                record(Listings.Sessions.class, "note"),
                call -> Listings.sessions(call.connection())));
        tools.add(tool(
                "list_windows",
                "List windows",
                "Lists windows, optionally only those in one named session.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TMUX_METADATA),
                true,
                true,
                List.of(optional("session", "Only windows in this session name.")),
                sinks(input("session", TMUX_LOOKUP)),
                record(Listings.Windows.class, "note"),
                Listings::windows));
        tools.add(tool(
                "list_panes",
                "List panes",
                "Lists pane metadata and stable pane IDs.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TMUX_METADATA),
                true,
                true,
                List.of(),
                Map.of(),
                record(Listings.Panes.class, "note")
                        .withPropertySchema(
                                "panes",
                                arrayOf(record(Listings.PaneSummary.class, "caller")
                                        .wireSchema())),
                Listings::panes));

        tools.add(inspectRichMetadata(
                "get_server_info",
                "Get server info",
                "Reports whether the pinned server exists and its version.",
                List.of(),
                Map.of(),
                shape(
                        field("running", BOOLEAN),
                        field("identity", STRING),
                        field("version", STRING),
                        field("sessions", INTEGER)),
                Operations::serverInfo));
        tools.add(inspectRichMetadata(
                "get_session_info",
                "Get session info",
                "Returns metadata for one session.",
                List.of(required("session_id", "The session ID, such as $1.")),
                sinks(input("session_id", TMUX_LOOKUP)),
                SESSION_OUTPUT,
                Operations::sessionInfo));
        tools.add(inspectRichMetadata(
                "get_window_info",
                "Get window info",
                "Returns metadata for one window.",
                List.of(required("window_id", "The window ID, such as @1.")),
                sinks(input("window_id", TMUX_LOOKUP)),
                WINDOW_OUTPUT,
                Operations::windowInfo));
        tools.add(inspectRichMetadata(
                "get_pane_info",
                "Get pane info",
                "Returns metadata for one pane.",
                List.of(paneId()),
                sinks(input("pane_id", TMUX_LOOKUP)),
                PANE_OUTPUT,
                Operations::paneInfo));

        List<Argument> capture = List.of(
                paneId(),
                flag("history", "Include scrollback rather than only the visible screen.", false),
                number("max_lines", "Maximum lines, keeping the newest.", Trim.DEFAULT_LINES));
        Map<String, Set<ToolSpec.InputSink>> captureSinks = sinks(
                input("pane_id", TMUX_LOOKUP),
                input("history", ToolSpec.InputSink.NONE),
                input("max_lines", ToolSpec.InputSink.NONE));
        tools.add(tool(
                "capture_pane",
                "Capture a pane",
                "Returns bounded pane content and a cursor.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                capture,
                captureSinks,
                record(Reading.Captured.class, "note"),
                Reading::capture));
        List<Argument> since = List.of(
                paneId(),
                optional("cursor", "A cursor returned by an earlier capture."),
                number("max_lines", "Maximum new lines, keeping the newest.", Trim.DEFAULT_LINES));
        tools.add(tool(
                "capture_since",
                "Capture new pane output",
                "Returns pane output produced after a cursor.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                since,
                sinks(
                        input("pane_id", TMUX_LOOKUP),
                        input("cursor", ToolSpec.InputSink.NONE),
                        input("max_lines", ToolSpec.InputSink.NONE)),
                record(Reading.Since.class, "note"),
                Reading::since));
        tools.add(tool(
                "snapshot_pane",
                "Snapshot a pane",
                "Returns pane metadata and bounded terminal content together.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                capture,
                captureSinks,
                shape(
                                field("pane", OBJECT),
                                field("content", ARRAY),
                                field("cursor", STRING),
                                field("truncated", BOOLEAN),
                                field("lines_dropped", INTEGER))
                        .withPropertySchema("content", arrayOf(Map.of("type", "string"))),
                Operations::snapshotPane));

        List<Argument> search = List.of(
                boundedRequired(
                        "pattern",
                        "The bounded text or regular expression to search for.",
                        TextPatterns.MAX_PATTERN_BYTES),
                flag("regex", "Treat pattern as a regular expression.", false),
                number("max_matches_per_pane", "Maximum matching lines per pane.", 5),
                number("max_lines", "Maximum matches across all panes.", Trim.DEFAULT_LINES));
        tools.add(tool(
                "search_panes",
                "Search panes",
                "Searches the visible output of every pane.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                search,
                sinks(
                        input("pattern", REGEX),
                        input("regex", ToolSpec.InputSink.NONE),
                        input("max_matches_per_pane", ToolSpec.InputSink.NONE),
                        input("max_lines", ToolSpec.InputSink.NONE)),
                record(Reading.Found.class, "note"),
                Reading::search));
        tools.add(inspectRichMetadata(
                "find_pane_by_position",
                "Find pane by position",
                "Finds a pane at one of a window's four corners.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        required("position", "top-left, top-right, bottom-left or bottom-right.")),
                sinks(input("window_id", TMUX_LOOKUP), input("position", TMUX_LOOKUP)),
                PANE_OUTPUT,
                Operations::findPaneByPosition));

        List<Argument> waitText = List.of(
                paneId(),
                boundedStrings(
                        "patterns",
                        "Text to wait for; any one ends the wait.",
                        TextPatterns.MAX_PATTERN_BYTES,
                        TextPatterns.MAX_PATTERNS),
                boundedStrings(
                        "stop",
                        "Failure text; any one ends the wait.",
                        TextPatterns.MAX_PATTERN_BYTES,
                        TextPatterns.MAX_PATTERNS),
                flag("regex", "Treat patterns and stops as regular expressions.", false),
                seconds("timeout", "Seconds to wait before giving up.", 30),
                optional("cursor", "A cursor returned by an earlier capture."),
                number("max_lines", "Maximum observed lines to return.", Trim.DEFAULT_LINES));
        tools.add(tool(
                "wait_for_text",
                "Wait for pane text",
                "Waits for new pane output without accepting executable input.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                waitText,
                sinks(
                        input("pane_id", TMUX_LOOKUP),
                        input("patterns", REGEX),
                        input("stop", REGEX),
                        input("regex", ToolSpec.InputSink.NONE),
                        input("timeout", ToolSpec.InputSink.NONE),
                        input("cursor", ToolSpec.InputSink.NONE),
                        input("max_lines", ToolSpec.InputSink.NONE)),
                record(WaitingForText.Waited.class, "matched", "matched_line", "note"),
                WaitingForText::waitFor));

        tools.add(tool(
                        "get_tmux_variables",
                        "Get tmux variables",
                        "Reads a capped list of validated tmux variable names, not free-form formats.",
                        INSPECT,
                        NONE,
                        effects(OBSERVE),
                        outputs(TMUX_METADATA, CONFIGURED_COMMAND),
                        true,
                        true,
                        List.of(
                                new Argument(
                                        "names",
                                        "array",
                                        "One to thirty-two variable names matching [A-Za-z][A-Za-z0-9_]*.",
                                        true,
                                        null,
                                        128,
                                        32),
                                optional("pane", "An optional pane context.")),
                        sinks(input("names", TMUX_LOOKUP, TMUX_FORMAT), input("pane", TMUX_LOOKUP)),
                        shape(field("values", OBJECT)),
                        Operations::tmuxVariables)
                .withInputLiteralization(Map.of("names", "validated-variable-name")));

        List<Argument> option = List.of(
                required("name", "The exact option name."),
                optional("scope", "global, server, session, window or pane."),
                optional("target", "The target required by session, window and pane scopes."),
                flag("effective", "Include an inherited value.", true));
        tools.add(tool(
                "show_option",
                "Show one option",
                "Reads one named tmux option.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TMUX_METADATA, CONFIGURED_COMMAND),
                true,
                true,
                option,
                sinks(
                        input("name", TMUX_LOOKUP),
                        input("scope", TMUX_LOOKUP),
                        input("target", TMUX_LOOKUP),
                        input("effective", ToolSpec.InputSink.NONE)),
                shape(field("scope", STRING), field("target", STRING), field("name", STRING), field("value", STRING)),
                Operations::showOption));
        tools.add(tool(
                "show_environment",
                "Show tmux environment",
                "Reads the environment tmux passes to processes.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(PROCESS_ENVIRONMENT),
                true,
                true,
                List.of(optional("session", "A session name; omit for the global environment.")),
                sinks(input("session", TMUX_LOOKUP)),
                record(Settings.Environment.class),
                Settings::environment));

        List<Argument> hooks = List.of(
                optional("scope", "global, server, session, window or pane."),
                optional("target", "The target required by session, window and pane scopes."),
                optional("name", "One hook name; omit to read all hooks in the scope."));
        tools.add(tool(
                "show_hooks",
                "Show hooks",
                "Reads configured tmux hooks.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(CONFIGURED_COMMAND),
                true,
                true,
                hooks,
                sinks(input("scope", TMUX_LOOKUP), input("target", TMUX_LOOKUP), input("name", TMUX_LOOKUP)),
                shape(field("scope", STRING), field("target", STRING), field("count", INTEGER), field("hooks", OBJECT)),
                Operations::showHooks));

        Set<String> nested = new LinkedHashSet<>(List.of(
                "list_sessions",
                "list_windows",
                "list_panes",
                "get_server_info",
                "get_session_info",
                "get_window_info",
                "get_pane_info",
                "capture_pane",
                "capture_since",
                "snapshot_pane",
                "search_panes",
                "find_pane_by_position",
                "get_tmux_variables",
                "show_option",
                "show_environment",
                "show_hooks"));
        tools.add(tool(
                "call_read_tools_batch",
                "Call read tools in a batch",
                "Calls up to sixteen eligible inspect tools serially; inner tools receive no separate approval, and its nested authority is disclosed. The complete JSON-RPC response is capped at 1,000,000 bytes; a removed nested envelope is marked on its row and counted in truncatedBytes.",
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TMUX_METADATA, TERMINAL_CONTENT, PROCESS_ENVIRONMENT, CONFIGURED_COMMAND),
                true,
                true,
                List.of(
                        objects("operations", "Objects with tool and optional arguments fields."),
                        optional("onError", "stop or continue; defaults to stop.")),
                sinks(input("operations", ToolSpec.InputSink.NESTED_TOOL), input("onError", ToolSpec.InputSink.NONE)),
                nested,
                readBatchOutput(),
                Operations::callReadToolsBatch));
    }

    private static void manage(List<ToolSpec> tools) {
        tools.add(literalized(
                manageTool(
                        "rename_session",
                        "Rename a session",
                        "Replaces a session's name.",
                        List.of(
                                required("session_id", "The session ID, such as $1."),
                                required("new_name", "The literal new session name.")),
                        sinks(input("session_id", TMUX_LOOKUP), input("new_name", TMUX_FORMAT)),
                        true,
                        SESSION_OUTPUT,
                        Operations::renameSession),
                "new_name"));
        tools.add(literalized(
                manageTool(
                        "rename_window",
                        "Rename a window",
                        "Replaces a window's name.",
                        List.of(
                                required("window_id", "The window ID, such as @1."),
                                required("new_name", "The literal new window name.")),
                        sinks(input("window_id", TMUX_LOOKUP), input("new_name", TMUX_FORMAT)),
                        true,
                        WINDOW_OUTPUT,
                        Operations::renameWindow),
                "new_name"));
        tools.add(manageTool(
                "select_window",
                "Select a window",
                "Makes one window active.",
                List.of(required("window_id", "The window ID, such as @1.")),
                sinks(input("window_id", TMUX_LOOKUP)),
                true,
                WINDOW_OUTPUT,
                Operations::selectWindow));
        tools.add(manageTool(
                "select_pane",
                "Select a pane",
                "Makes one pane active.",
                List.of(paneId()),
                sinks(input("pane_id", TMUX_LOOKUP)),
                true,
                PANE_OUTPUT,
                Operations::selectPane));
        tools.add(manageTool(
                "select_layout",
                "Select a layout",
                "Applies one built-in tmux layout.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        required("layout", "A built-in layout name.")),
                sinks(input("window_id", TMUX_LOOKUP), input("layout", TMUX_STATE)),
                false,
                record(Shaping.Changed.class, "note"),
                Shaping::selectLayout));
        tools.add(manageTool(
                "resize_window",
                "Resize a window",
                "Sets a window's width, height or both.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        number("width", "Width in terminal cells; omit to retain it.", 0),
                        number("height", "Height in terminal cells; omit to retain it.", 0)),
                sinks(input("window_id", TMUX_LOOKUP), input("width", TMUX_STATE), input("height", TMUX_STATE)),
                true,
                WINDOW_OUTPUT,
                Operations::resizeWindow));
        tools.add(manageTool(
                "resize_pane",
                "Resize a pane",
                "Sets a pane's width, height or both.",
                List.of(
                        paneId(),
                        number("width", "Width in terminal cells; omit to retain it.", 0),
                        number("height", "Height in terminal cells; omit to retain it.", 0)),
                sinks(input("pane_id", TMUX_LOOKUP), input("width", TMUX_STATE), input("height", TMUX_STATE)),
                false,
                record(Shaping.Changed.class, "note"),
                Shaping::resizePane));
        tools.add(manageTool(
                "move_window",
                "Move a window",
                "Moves a window to another session, optionally at an index.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        required("session_id", "The destination session ID, such as $1."),
                        number("index", "A destination window index; omit for tmux's choice.", -1)),
                sinks(input("window_id", TMUX_LOOKUP), input("session_id", TMUX_LOOKUP), input("index", TMUX_STATE)),
                false,
                shape(field("window_id", STRING), field("session_id", STRING), field("index", INTEGER)),
                Operations::moveWindow));
        tools.add(manageTool(
                "swap_pane",
                "Swap panes",
                "Swaps the positions of two panes.",
                List.of(paneId(), required("other_pane_id", "The other pane ID, such as %2.")),
                sinks(input("pane_id", TMUX_LOOKUP), input("other_pane_id", TMUX_LOOKUP)),
                false,
                shape(field("pane_id", STRING), field("other_pane_id", STRING)),
                Operations::swapPane));
        tools.add(literalized(
                manageTool(
                        "set_pane_title",
                        "Set pane title",
                        "Replaces a pane's literal title.",
                        List.of(paneId(), required("title", "The literal title.")),
                        sinks(input("pane_id", TMUX_LOOKUP), input("title", TMUX_FORMAT)),
                        true,
                        PANE_OUTPUT,
                        Operations::setPaneTitle),
                "title"));
        List<Argument> channelWait = List.of(
                required("channel", "A server-wide tmux channel name."),
                seconds("timeout", "Seconds to wait before giving up.", 30),
                flag("drain_first", "Consume a pending signal before waiting.", false));
        tools.add(tool(
                "wait_for_channel",
                "Wait for a channel",
                "Waits on tmux's channel state with a bounded timeout.",
                MANAGE,
                NONE,
                effects(CHANGE),
                outputs(TMUX_METADATA),
                true,
                true,
                channelWait,
                sinks(
                        input("channel", TMUX_STATE),
                        input("timeout", ToolSpec.InputSink.NONE),
                        input("drain_first", TMUX_STATE)),
                record(Channels.Woke.class, "note"),
                Channels::waitFor));
        tools.add(changeOnlyTool(
                "signal_channel",
                "Signal a channel",
                "Signals one server-wide tmux channel.",
                List.of(required("channel", "The channel name.")),
                sinks(input("channel", TMUX_STATE)),
                true,
                record(Channels.Signalled.class),
                Channels::signal));
        tools.add(changeOnlyTool(
                "set_mouse_enabled",
                "Set mouse handling",
                "Enables or disables tmux mouse handling.",
                List.of(flag("enabled", "Whether mouse handling is enabled.", false)),
                sinks(input("enabled", TMUX_STATE)),
                false,
                shape(field("enabled", BOOLEAN)),
                Operations::setMouseEnabled));
        tools.add(changeOnlyTool(
                "set_history_limit",
                "Set history limit",
                "Sets a bounded integer scrollback limit for future panes in a session.",
                List.of(
                        required("session_id", "The session ID, such as $1."),
                        requiredNumber("lines", "The nonnegative retained line count.")),
                sinks(input("session_id", TMUX_LOOKUP), input("lines", TMUX_STATE)),
                false,
                shape(field("session_id", STRING), field("lines", INTEGER)),
                Operations::setHistoryLimit));
    }

    private static void execute(List<ToolSpec> tools) {
        tools.add(literalized(
                tool(
                        "create_session",
                        "Create a session",
                        "Creates a detached session whose first pane runs the configured process.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        effects(OBSERVE, CHANGE),
                        outputs(TMUX_METADATA),
                        true,
                        true,
                        List.of(
                                optional("session_name", "A literal session name."),
                                optional("window_name", "A literal first-window name."),
                                optional("start_directory", "An absolute literal start directory."),
                                number("width", "Initial width; supply with height.", -1),
                                number("height", "Initial height; supply with width.", -1)),
                        sinks(
                                input("session_name", TMUX_FORMAT),
                                input("window_name", TMUX_FORMAT),
                                input("start_directory", TMUX_FORMAT),
                                input("width", TMUX_STATE),
                                input("height", TMUX_STATE)),
                        SESSION_OUTPUT,
                        Operations::createSession),
                "session_name",
                "window_name",
                "start_directory"));
        tools.add(literalized(
                tool(
                        "create_window",
                        "Create a window",
                        "Creates a window whose first pane runs the configured process.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        effects(OBSERVE, CHANGE),
                        outputs(TMUX_METADATA),
                        true,
                        true,
                        List.of(
                                required("session_id", "The session ID, such as $1."),
                                optional("window_name", "A literal window name."),
                                optional("start_directory", "An absolute literal start directory."),
                                flag("attach", "Make the new window active.", false),
                                optional("direction", "before or after.")),
                        sinks(
                                input("session_id", TMUX_LOOKUP),
                                input("window_name", TMUX_FORMAT),
                                input("start_directory", TMUX_FORMAT),
                                input("attach", TMUX_STATE),
                                input("direction", TMUX_STATE)),
                        WINDOW_OUTPUT,
                        Operations::createWindow),
                "window_name",
                "start_directory"));
        tools.add(literalized(
                tool(
                        "split_window",
                        "Split a window",
                        "Creates a pane whose configured process starts after the split.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        effects(OBSERVE, CHANGE),
                        outputs(TMUX_METADATA),
                        true,
                        true,
                        List.of(
                                paneId(),
                                optional("direction", "below, above, left or right."),
                                number("percent", "Share of the split occupied by the new pane.", 50),
                                optional("start_directory", "An absolute literal start directory.")),
                        sinks(
                                input("pane_id", TMUX_LOOKUP),
                                input("direction", TMUX_STATE),
                                input("percent", TMUX_STATE),
                                input("start_directory", TMUX_FORMAT)),
                        PANE_OUTPUT,
                        Operations::splitWindow),
                "start_directory"));
        tools.add(literalized(
                tool(
                        "respawn_pane",
                        "Respawn a pane",
                        "Kills the pane's current process and starts its configured process again.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        effects(OBSERVE, CHANGE, DELETE),
                        outputs(TMUX_METADATA),
                        false,
                        false,
                        List.of(paneId(), optional("start_directory", "An absolute literal start directory.")),
                        sinks(input("pane_id", TMUX_LOOKUP), input("start_directory", TMUX_FORMAT)),
                        shape(field("pane_id", STRING), field("restarted", BOOLEAN)),
                        Operations::respawnPane),
                "start_directory"));

        List<Argument> run = List.of(
                paneId(),
                required("command", "The shell command, run in the pane's interactive shell."),
                seconds("timeout", "Seconds to wait before giving up.", 30),
                number("max_lines", "Maximum output lines, keeping the newest.", Trim.DEFAULT_LINES),
                flag("suppress_history", "Best-effort persistent history suppression.", true));
        tools.add(tool(
                "run_shell_command",
                "Run a shell command",
                "Runs one authored command in a trusted pane shell and waits for singular framed output and "
                        + "completion. It refuses an effective cohort larger than one at either of two preflights. "
                        + "Pre-existing exact-client-path, trap, eval, or exit functions are outside the supported "
                        + "boundary; marker display-message commands honor the trusted server's command aliases "
                        + "and hooks.",
                EXECUTE,
                PANE_COMMAND,
                effects(OBSERVE, CHANGE),
                outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                run,
                sinks(
                        input("pane_id", TMUX_LOOKUP),
                        input("command", ToolSpec.InputSink.PANE_INPUT, SHELL_COMMAND),
                        input("timeout", ToolSpec.InputSink.NONE),
                        input("max_lines", ToolSpec.InputSink.NONE),
                        input("suppress_history", ToolSpec.InputSink.NONE)),
                record(RunningCommands.Ran.class, "exit_status", "note"),
                RunningCommands::run));

        List<Argument> keys = List.of(
                paneId(),
                strings("keys", "The key names or literal strings to send."),
                flag("literal", "Send strings literally instead of as key names.", false));
        tools.add(tool(
                "send_keys",
                "Send keys",
                "Sends input to the target's configured effective synchronized cohort without waiting for output. "
                        + "Reports configured pane ids observed before dispatch, not delivery receipts.",
                EXECUTE,
                PANE_INPUT,
                effects(OBSERVE, CHANGE),
                outputs(TMUX_METADATA),
                false,
                false,
                keys,
                sinks(
                        input("pane_id", TMUX_LOOKUP),
                        input("keys", ToolSpec.InputSink.PANE_INPUT),
                        input("literal", ToolSpec.InputSink.NONE)),
                record(Typing.Sent.class, "note"),
                Typing::sendKeys));
        tools.add(tool(
                "send_keys_batch",
                "Send keys in a batch",
                "Sends up to sixty-four ordered pane-input operations, resolving and guarding the configured "
                        + "effective cohort separately for each ordered operation. A later policy or dispatch "
                        + "failure retains observed membership.",
                EXECUTE,
                PANE_INPUT,
                effects(OBSERVE, CHANGE),
                outputs(TMUX_METADATA),
                true,
                true,
                List.of(
                        boundedObjects("operations", "Objects with pane_id, keys and optional literal fields.", 64),
                        optional("onError", "stop or continue; defaults to stop.")),
                sinks(
                        input("operations", TMUX_LOOKUP, ToolSpec.InputSink.PANE_INPUT),
                        input("onError", ToolSpec.InputSink.NONE)),
                sendBatchOutput(),
                Operations::sendKeysBatch));
        tools.add(tool(
                "paste_text",
                "Paste text",
                "Pastes one literal text block into one target pane through an ephemeral buffer; paste-buffer "
                        + "input does not fan out to synchronized peers.",
                EXECUTE,
                PANE_INPUT,
                effects(OBSERVE, CHANGE),
                outputs(TMUX_METADATA),
                false,
                false,
                List.of(
                        paneId(),
                        required("text", "The literal text to paste."),
                        flag("enter", "Append a newline that submits the text.", false)),
                sinks(
                        input("pane_id", TMUX_LOOKUP),
                        input("text", ToolSpec.InputSink.PANE_INPUT),
                        input("enter", ToolSpec.InputSink.PANE_INPUT)),
                record(Typing.Pasted.class, "note"),
                Typing::pasteText));
        tools.add(amplifying(tool(
                "set_synchronize_panes",
                "Set synchronized panes",
                "When enabled, sets the inherited window default; pane-level overrides determine each pane's "
                        + "effective synchronized value.",
                EXECUTE,
                NONE,
                effects(CHANGE),
                outputs(TMUX_METADATA),
                false,
                false,
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        flag("enabled", "Whether pane input is synchronized.", false)),
                sinks(input("window_id", TMUX_LOOKUP), input("enabled", TMUX_STATE)),
                shape(field("window_id", STRING), field("enabled", BOOLEAN)),
                Operations::setSynchronizePanes)));
    }

    private static void teardown(List<ToolSpec> tools) {
        tools.add(deleteOnlyTool(
                "clear_pane_scrollback",
                "Clear pane scrollback",
                "Deletes retained scrollback from one pane.",
                List.of(paneId()),
                sinks(input("pane_id", TMUX_LOOKUP)),
                shape(field("pane_id", STRING), field("cleared", BOOLEAN)),
                Operations::clearPaneScrollback));
        tools.add(teardownTool(
                "kill_pane",
                "Kill a pane",
                "Deletes one pane and ends its process.",
                killArguments("pane_id", "The pane ID, such as %1."),
                sinks(input("pane_id", TMUX_LOOKUP), input("confirm_self", ToolSpec.InputSink.NONE)),
                record(Shaping.Ended.class, "note"),
                Operations::killPane));
        tools.add(teardownTool(
                "kill_window",
                "Kill a window",
                "Deletes one window and every pane in it.",
                killArguments("window_id", "The window ID, such as @1."),
                sinks(input("window_id", TMUX_LOOKUP), input("confirm_self", ToolSpec.InputSink.NONE)),
                record(Shaping.Ended.class, "note"),
                Operations::killWindow));
        tools.add(teardownTool(
                "kill_session",
                "Kill a session",
                "Deletes one session and every window and pane in it.",
                killArguments("session_id", "The session ID, such as $1."),
                sinks(input("session_id", TMUX_LOOKUP), input("confirm_self", ToolSpec.InputSink.NONE)),
                record(Shaping.Ended.class, "note"),
                Operations::killSession));
    }

    private static List<Argument> killArguments(String name, String description) {
        return List.of(
                required(name, description),
                flag("confirm_self", "Permit ending the pane this MCP process runs in.", false));
    }

    private static ToolSpec inspectRichMetadata(
            String name,
            String title,
            String details,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                INSPECT,
                NONE,
                effects(OBSERVE),
                outputs(TMUX_METADATA),
                true,
                true,
                arguments,
                sinks,
                output,
                answer);
    }

    private static ToolSpec manageTool(
            String name,
            String title,
            String details,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            boolean richOutput,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                MANAGE,
                NONE,
                effects(OBSERVE, CHANGE),
                outputs(TMUX_METADATA),
                richOutput,
                richOutput,
                arguments,
                sinks,
                output,
                answer);
    }

    private static ToolSpec changeOnlyTool(
            String name,
            String title,
            String details,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            boolean richOutput,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                MANAGE,
                NONE,
                effects(CHANGE),
                outputs(TMUX_METADATA),
                richOutput,
                richOutput,
                arguments,
                sinks,
                output,
                answer);
    }

    private static ToolSpec teardownTool(
            String name,
            String title,
            String details,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                TEARDOWN,
                NONE,
                effects(OBSERVE, DELETE),
                outputs(TMUX_METADATA),
                false,
                false,
                arguments,
                sinks,
                output,
                answer);
    }

    private static ToolSpec deleteOnlyTool(
            String name,
            String title,
            String details,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                TEARDOWN,
                NONE,
                effects(DELETE),
                outputs(TMUX_METADATA),
                false,
                false,
                arguments,
                sinks,
                output,
                answer);
    }

    private static ToolSpec tool(
            String name,
            String title,
            String details,
            ToolSpec.Toolset toolset,
            ToolSpec.ProcessReach processReach,
            Set<ToolSpec.TmuxEffect> effects,
            Set<ToolSpec.OutputClass> outputs,
            boolean mayExposeSecrets,
            boolean mayReturnUntrustedContent,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                toolset,
                processReach,
                effects,
                outputs,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                arguments,
                sinks,
                Set.of(),
                output,
                answer);
    }

    private static ToolSpec tool(
            String name,
            String title,
            String details,
            ToolSpec.Toolset toolset,
            ToolSpec.ProcessReach processReach,
            Set<ToolSpec.TmuxEffect> effects,
            Set<ToolSpec.OutputClass> outputs,
            boolean mayExposeSecrets,
            boolean mayReturnUntrustedContent,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            Set<String> nestedAuthority,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return ToolSpec.define(
                name,
                title,
                details,
                toolset,
                processReach,
                effects,
                outputs,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                conservativeAnnotations(),
                arguments,
                sinks,
                nestedAuthority,
                output,
                answer);
    }

    private static ToolSpec.Annotations conservativeAnnotations() {
        return new ToolSpec.Annotations(false, true, false, true);
    }

    private static ToolSpec literalized(ToolSpec tool, String... fields) {
        Map<String, String> claims = new LinkedHashMap<>();
        for (String field : fields) {
            claims.put(field, "double-hash-once");
        }
        return tool.withInputLiteralization(claims);
    }

    private static ToolSpec amplifying(ToolSpec tool) {
        return tool.amplifyingFutureInput();
    }

    @SafeVarargs
    private static <E extends Enum<E>> Set<E> enums(E first, E... rest) {
        Set<E> values = EnumSet.noneOf(first.getDeclaringClass());
        values.add(first);
        for (E value : rest) {
            values.add(value);
        }
        return values;
    }

    private static Set<ToolSpec.TmuxEffect> effects(ToolSpec.TmuxEffect first, ToolSpec.TmuxEffect... rest) {
        return enums(first, rest);
    }

    private static Set<ToolSpec.OutputClass> outputs(ToolSpec.OutputClass first, ToolSpec.OutputClass... rest) {
        return enums(first, rest);
    }

    private static Input input(String name, ToolSpec.InputSink first, ToolSpec.InputSink... rest) {
        return new Input(name, enums(first, rest));
    }

    private static Map<String, Set<ToolSpec.InputSink>> sinks(Input... inputs) {
        Map<String, Set<ToolSpec.InputSink>> sinks = new LinkedHashMap<>();
        for (Input input : inputs) {
            if (sinks.put(input.name(), input.sinks()) != null) {
                throw new IllegalArgumentException("duplicate sink declaration for '" + input.name() + "'");
            }
        }
        return sinks;
    }

    private static OutputSchema shape(OutputSchema.Field first, OutputSchema.Field... rest) {
        return OutputSchema.of(first, rest);
    }

    private static OutputSchema record(Class<?> type, String... optionalFields) {
        return OutputSchema.ofRecord(type).withOptionalFields(optionalFields);
    }

    private static OutputSchema.Field field(String name, OutputSchema.ValueType type) {
        return new OutputSchema.Field(name, type);
    }

    private static OutputSchema readBatchOutput() {
        OutputSchema envelope = shape(
                        field("_meta", OBJECT),
                        field("content", ARRAY),
                        field("structuredContent", OBJECT),
                        field("isError", BOOLEAN))
                .withOptionalFields("_meta", "structuredContent")
                .withPropertySchema("content", arrayOf(Map.of("type", "object")));
        OutputSchema row = shape(
                        field("index", INTEGER),
                        field("tool", STRING),
                        field("success", BOOLEAN),
                        field("error", STRING),
                        field("result", OBJECT),
                        field("resultTruncated", BOOLEAN))
                .withPropertySchema("error", nullable(Map.of("type", "string")))
                .withPropertySchema("result", nullable(envelope.wireSchema()));
        return shape(
                        field("results", ARRAY),
                        field("succeeded", INTEGER),
                        field("failed", INTEGER),
                        field("stoppedAt", INTEGER),
                        field("truncated", BOOLEAN),
                        field("truncatedBytes", INTEGER),
                        field("onError", STRING))
                .withPropertySchema("results", arrayOf(row.wireSchema()))
                .withPropertySchema("stoppedAt", nullable(Map.of("type", "integer")));
    }

    private static OutputSchema sendBatchOutput() {
        OutputSchema row = shape(
                        field("index", INTEGER),
                        field("pane_id", STRING),
                        field("resolved_pane_ids", ARRAY),
                        field("success", BOOLEAN),
                        field("error", STRING))
                .withOptionalFields("error")
                .withPropertySchema("resolved_pane_ids", arrayOf(Map.of("type", "string")));
        return shape(field("results", ARRAY), field("completed", INTEGER))
                .withPropertySchema("results", arrayOf(row.wireSchema()));
    }

    private static Map<String, Object> arrayOf(Map<String, Object> item) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", item);
        return java.util.Collections.unmodifiableMap(schema);
    }

    private static Map<String, Object> nullable(Map<String, Object> value) {
        return Map.of("oneOf", List.of(value, Map.of("type", "null")));
    }

    private static void validateSchema(ToolSpec tool) {
        Set<String> schema = new LinkedHashSet<>();
        for (Argument argument : tool.arguments()) {
            if (!schema.add(argument.name())) {
                throw new IllegalArgumentException(
                        tool.name() + " has duplicate schema field '" + argument.name() + "'");
            }
            if (Set.of("socket", "socket_name", "socket_path").contains(argument.name())) {
                throw new IllegalArgumentException(tool.name() + " exposes a per-call socket selector");
            }
        }
        if (!schema.equals(tool.inputSinks().keySet())) {
            Set<String> missing = new HashSet<>(schema);
            missing.removeAll(tool.inputSinks().keySet());
            Set<String> extra = new HashSet<>(tool.inputSinks().keySet());
            extra.removeAll(schema);
            throw new IllegalArgumentException(
                    tool.name() + " sink/schema mismatch; missing=" + missing + ", extra=" + extra);
        }
    }

    private static void validateReach(ToolSpec tool) {
        Set<ToolSpec.InputSink> sinks = tool.inputSinks().values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean paneInput = sinks.contains(ToolSpec.InputSink.PANE_INPUT);
        boolean shellCommand = sinks.contains(SHELL_COMMAND);
        boolean processArgv = sinks.contains(ToolSpec.InputSink.PROCESS_ARGV);
        switch (tool.processReach()) {
            case NONE -> {
                if (paneInput || shellCommand || processArgv) {
                    throw new IllegalArgumentException(tool.name() + " has executable sinks with reach none");
                }
            }
            case CONFIGURED_PROCESS -> {
                if (paneInput || shellCommand || processArgv) {
                    throw new IllegalArgumentException(tool.name() + " misstates configured-process reach");
                }
            }
            case PANE_INPUT -> {
                if (!paneInput || shellCommand || processArgv) {
                    throw new IllegalArgumentException(tool.name() + " pane-input reach disagrees with its sinks");
                }
            }
            case PANE_COMMAND -> {
                if (!shellCommand || processArgv) {
                    throw new IllegalArgumentException(
                            tool.name() + " pane-command reach disagrees with its shell-command sink");
                }
            }
            case HOST_COMMAND -> throw new IllegalArgumentException(tool.name() + " exposes host-command reach");
        }
        if (tool.toolset() == INSPECT
                && (tool.processReach() != NONE
                        || !tool.effects().contains(OBSERVE)
                        || tool.effects().contains(DELETE))) {
            throw new IllegalArgumentException(tool.name() + " is not observational inspect authority");
        }
        if (tool.toolset() == MANAGE && tool.processReach() != NONE) {
            throw new IllegalArgumentException(tool.name() + " manage authority reaches a workload process");
        }
        if (tool.toolset() == EXECUTE
                && tool.processReach() == NONE
                && !tool.name().equals("set_synchronize_panes")) {
            throw new IllegalArgumentException(tool.name() + " execute authority has no process reach");
        }
        if (tool.toolset() == TEARDOWN
                && (tool.processReach() != NONE || !tool.effects().contains(DELETE))) {
            throw new IllegalArgumentException(tool.name() + " is not direct teardown authority");
        }
    }

    private record Input(String name, Set<ToolSpec.InputSink> sinks) {
        Input {
            Objects.requireNonNull(name, "name");
            sinks = Set.copyOf(sinks);
        }
    }
}
