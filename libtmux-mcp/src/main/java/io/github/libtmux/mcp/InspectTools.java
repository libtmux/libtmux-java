package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.Argument.boundedRequired;
import static io.github.libtmux.mcp.Argument.boundedStrings;
import static io.github.libtmux.mcp.Argument.flag;
import static io.github.libtmux.mcp.Argument.number;
import static io.github.libtmux.mcp.Argument.objects;
import static io.github.libtmux.mcp.Argument.optional;
import static io.github.libtmux.mcp.Argument.paneId;
import static io.github.libtmux.mcp.Argument.required;
import static io.github.libtmux.mcp.Argument.seconds;
import static io.github.libtmux.mcp.OutputSchema.ValueType.ARRAY;
import static io.github.libtmux.mcp.OutputSchema.ValueType.BOOLEAN;
import static io.github.libtmux.mcp.OutputSchema.ValueType.INTEGER;
import static io.github.libtmux.mcp.OutputSchema.ValueType.OBJECT;
import static io.github.libtmux.mcp.OutputSchema.ValueType.STRING;
import static io.github.libtmux.mcp.ToolSpec.InputSink.REGEX;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_FORMAT;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_LOOKUP;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.CONFIGURED_COMMAND;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.PROCESS_ENVIRONMENT;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.TERMINAL_CONTENT;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.TMUX_METADATA;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.NONE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.OBSERVE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.INSPECT;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The inspect toolset: read-only tmux and pane observation, accepting no executable input. */
final class InspectTools {

    private InspectTools() {}

    static void inspect(List<ToolSpec> tools) {
        tools.add(Catalog.tool(
                "list_sessions",
                "List sessions",
                "Lists sessions on the pinned tmux server.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TMUX_METADATA),
                true,
                true,
                List.of(),
                Map.of(),
                Catalog.record(Listings.Sessions.class, "note"),
                call -> Listings.sessions(call.connection())));
        tools.add(Catalog.tool(
                "list_windows",
                "List windows",
                "Lists windows, optionally only those in one named session.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TMUX_METADATA),
                true,
                true,
                List.of(optional("session", "Only windows in this session name.")),
                Catalog.sinks(Catalog.input("session", TMUX_LOOKUP)),
                Catalog.record(Listings.Windows.class, "note"),
                Listings::windows));
        tools.add(Catalog.tool(
                "list_panes",
                "List panes",
                "Lists pane metadata and stable pane IDs. No filter; use the 'command' field to find"
                        + " a pane by what it runs.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TMUX_METADATA),
                true,
                true,
                List.of(),
                Map.of(),
                Catalog.record(Listings.Panes.class, "note")
                        .withPropertySchema(
                                "panes",
                                Catalog.arrayOf(Catalog.record(Listings.PaneSummary.class, "caller")
                                        .wireSchema())),
                Listings::panes));

        tools.add(inspectRichMetadata(
                "get_server_info",
                "Get server info",
                "Reports whether the pinned server exists and its version.",
                List.of(),
                Map.of(),
                Catalog.shape(
                        Catalog.field("running", BOOLEAN),
                        Catalog.field("identity", STRING),
                        Catalog.field("version", STRING),
                        Catalog.field("sessions", INTEGER)),
                Operations::serverInfo));
        tools.add(inspectRichMetadata(
                "get_session_info",
                "Get session info",
                "Returns metadata for one session.",
                List.of(required("session_id", "The session ID, such as $1.")),
                Catalog.sinks(Catalog.input("session_id", TMUX_LOOKUP)),
                Catalog.SESSION_OUTPUT,
                Operations::sessionInfo));
        tools.add(inspectRichMetadata(
                "get_window_info",
                "Get window info",
                "Returns metadata for one window.",
                List.of(required("window_id", "The window ID, such as @1.")),
                Catalog.sinks(Catalog.input("window_id", TMUX_LOOKUP)),
                Catalog.WINDOW_OUTPUT,
                Operations::windowInfo));
        tools.add(inspectRichMetadata(
                "get_pane_info",
                "Get pane info",
                "Returns metadata for one pane.",
                List.of(paneId()),
                Catalog.sinks(Catalog.input("pane_id", TMUX_LOOKUP)),
                Catalog.PANE_OUTPUT,
                Operations::paneInfo));

        List<Argument> capture = List.of(
                paneId(),
                flag("history", "Include scrollback rather than only the visible screen.", false),
                number("max_lines", "Maximum lines, keeping the newest.", Trim.DEFAULT_LINES));
        Map<String, Set<ToolSpec.InputSink>> captureSinks = Catalog.sinks(
                Catalog.input("pane_id", TMUX_LOOKUP),
                Catalog.input("history", ToolSpec.InputSink.NONE),
                Catalog.input("max_lines", ToolSpec.InputSink.NONE));
        tools.add(Catalog.tool(
                "capture_pane",
                "Capture a pane",
                "Returns bounded pane content and a cursor.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                capture,
                captureSinks,
                Catalog.record(Reading.Captured.class, "note"),
                Reading::capture));
        List<Argument> since = List.of(
                paneId(),
                optional("cursor", "A cursor returned by an earlier capture."),
                number("max_lines", "Maximum new lines, keeping the newest.", Trim.DEFAULT_LINES));
        tools.add(Catalog.tool(
                "capture_since",
                "Capture new pane output",
                "Returns pane output produced after a cursor.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                since,
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP),
                        Catalog.input("cursor", ToolSpec.InputSink.NONE),
                        Catalog.input("max_lines", ToolSpec.InputSink.NONE)),
                Catalog.record(Reading.Since.class, "note"),
                Reading::since));
        tools.add(Catalog.tool(
                "snapshot_pane",
                "Snapshot a pane",
                "Returns pane metadata and bounded terminal content together.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                capture,
                captureSinks,
                Catalog.shape(
                                Catalog.field("pane", OBJECT),
                                Catalog.field("content", ARRAY),
                                Catalog.field("cursor", STRING),
                                Catalog.field("truncated", BOOLEAN),
                                Catalog.field("lines_dropped", INTEGER))
                        .withPropertySchema("content", Catalog.arrayOf(Map.of("type", "string"))),
                Operations::snapshotPane));

        List<Argument> search = List.of(
                boundedRequired(
                        "pattern",
                        "The bounded text or regular expression to search for.",
                        TextPatterns.MAX_PATTERN_BYTES),
                flag("regex", "Treat pattern as a regular expression.", false),
                number("max_matches_per_pane", "Maximum matching lines per pane.", 5),
                number("max_lines", "Maximum matches across all panes.", Trim.DEFAULT_LINES));
        tools.add(Catalog.tool(
                "search_panes",
                "Search panes",
                "Searches the visible output of every pane.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                search,
                Catalog.sinks(
                        Catalog.input("pattern", REGEX),
                        Catalog.input("regex", ToolSpec.InputSink.NONE),
                        Catalog.input("max_matches_per_pane", ToolSpec.InputSink.NONE),
                        Catalog.input("max_lines", ToolSpec.InputSink.NONE)),
                Catalog.record(Reading.Found.class, "note"),
                Reading::search));
        tools.add(inspectRichMetadata(
                "find_pane_by_position",
                "Find pane by position",
                "Finds a pane at one of a window's four corners.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        required("position", "top-left, top-right, bottom-left or bottom-right.")),
                Catalog.sinks(Catalog.input("window_id", TMUX_LOOKUP), Catalog.input("position", TMUX_LOOKUP)),
                Catalog.PANE_OUTPUT,
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
        tools.add(Catalog.tool(
                "wait_for_text",
                "Wait for pane text",
                "Waits for new pane output without accepting executable input. Discounts recognized input"
                        + " from this server while pending and for ten seconds after submission. Output identical"
                        + " to that input and partially redrawn echoes are ambiguous; use run_shell_command for"
                        + " commands you start. Wait for the"
                        + " prompt before typing into a cold shell.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                waitText,
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP),
                        Catalog.input("patterns", REGEX),
                        Catalog.input("stop", REGEX),
                        Catalog.input("regex", ToolSpec.InputSink.NONE),
                        Catalog.input("timeout", ToolSpec.InputSink.NONE),
                        Catalog.input("cursor", ToolSpec.InputSink.NONE),
                        Catalog.input("max_lines", ToolSpec.InputSink.NONE)),
                Catalog.record(WaitingForText.Waited.class, "matched", "matched_line", "note"),
                WaitingForText::waitFor));

        tools.add(Catalog.tool(
                        "get_tmux_variables",
                        "Get tmux variables",
                        "Reads a capped list of validated tmux variable names, not free-form formats.",
                        INSPECT,
                        NONE,
                        Catalog.effects(OBSERVE),
                        Catalog.outputs(TMUX_METADATA, CONFIGURED_COMMAND),
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
                        Catalog.sinks(
                                Catalog.input("names", TMUX_LOOKUP, TMUX_FORMAT), Catalog.input("pane", TMUX_LOOKUP)),
                        Catalog.shape(Catalog.field("values", OBJECT)),
                        Operations::tmuxVariables)
                .withInputLiteralization(Map.of("names", "validated-variable-name")));

        List<Argument> option = List.of(
                required("name", "The exact option name."),
                optional("scope", "global, server, session, window or pane."),
                optional("target", "The target required by session, window and pane scopes."),
                flag("effective", "Include an inherited value.", true));
        tools.add(Catalog.tool(
                "show_option",
                "Show one option",
                "Reads one named tmux option.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TMUX_METADATA, CONFIGURED_COMMAND),
                true,
                true,
                option,
                Catalog.sinks(
                        Catalog.input("name", TMUX_LOOKUP),
                        Catalog.input("scope", TMUX_LOOKUP),
                        Catalog.input("target", TMUX_LOOKUP),
                        Catalog.input("effective", ToolSpec.InputSink.NONE)),
                Catalog.shape(
                        Catalog.field("scope", STRING),
                        Catalog.field("target", STRING),
                        Catalog.field("name", STRING),
                        Catalog.field("value", STRING)),
                Operations::showOption));
        tools.add(Catalog.tool(
                "show_environment",
                "Show tmux environment",
                "Reads the environment tmux passes to processes.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(PROCESS_ENVIRONMENT),
                true,
                true,
                List.of(optional("session", "A session name; omit for the global environment.")),
                Catalog.sinks(Catalog.input("session", TMUX_LOOKUP)),
                Catalog.record(Settings.Environment.class),
                Settings::environment));

        List<Argument> hooks = List.of(
                optional("scope", "global, server, session, window or pane."),
                optional("target", "The target required by session, window and pane scopes."),
                optional("name", "One hook name; omit to read all hooks in the scope."));
        tools.add(Catalog.tool(
                "show_hooks",
                "Show hooks",
                "Reads configured tmux hooks.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(CONFIGURED_COMMAND),
                true,
                true,
                hooks,
                Catalog.sinks(
                        Catalog.input("scope", TMUX_LOOKUP),
                        Catalog.input("target", TMUX_LOOKUP),
                        Catalog.input("name", TMUX_LOOKUP)),
                Catalog.shape(
                        Catalog.field("scope", STRING),
                        Catalog.field("target", STRING),
                        Catalog.field("count", INTEGER),
                        Catalog.field("hooks", OBJECT)),
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
        tools.add(Catalog.tool(
                "call_read_tools_batch",
                "Call read tools in a batch",
                "Calls up to sixteen eligible inspect tools serially; inner tools receive no separate approval, and its nested authority is disclosed. The complete JSON-RPC response is capped at 1,000,000 bytes; a removed nested envelope is marked on its row and counted in truncatedBytes.",
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TMUX_METADATA, TERMINAL_CONTENT, PROCESS_ENVIRONMENT, CONFIGURED_COMMAND),
                true,
                true,
                List.of(
                        objects("operations", "Objects with tool and optional arguments fields."),
                        optional("onError", "stop or continue; defaults to stop.")),
                Catalog.sinks(
                        Catalog.input("operations", ToolSpec.InputSink.NESTED_TOOL),
                        Catalog.input("onError", ToolSpec.InputSink.NONE)),
                nested,
                readBatchOutput(),
                Operations::callReadToolsBatch));
    }

    private static ToolSpec inspectRichMetadata(
            String name,
            String title,
            String details,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return Catalog.tool(
                name,
                title,
                details,
                INSPECT,
                NONE,
                Catalog.effects(OBSERVE),
                Catalog.outputs(TMUX_METADATA),
                true,
                true,
                arguments,
                sinks,
                output,
                answer);
    }

    private static OutputSchema readBatchOutput() {
        OutputSchema envelope = Catalog.shape(
                        Catalog.field("_meta", OBJECT),
                        Catalog.field("content", ARRAY),
                        Catalog.field("structuredContent", OBJECT),
                        Catalog.field("isError", BOOLEAN))
                .withOptionalFields("_meta", "structuredContent")
                .withPropertySchema("content", Catalog.arrayOf(Map.of("type", "object")));
        OutputSchema row = Catalog.shape(
                        Catalog.field("index", INTEGER),
                        Catalog.field("tool", STRING),
                        Catalog.field("success", BOOLEAN),
                        Catalog.field("error", STRING),
                        Catalog.field("result", OBJECT),
                        Catalog.field("resultTruncated", BOOLEAN))
                .withPropertySchema("error", nullable(Map.of("type", "string")))
                .withPropertySchema("result", nullable(envelope.wireSchema()));
        return Catalog.shape(
                        Catalog.field("results", ARRAY),
                        Catalog.field("succeeded", INTEGER),
                        Catalog.field("failed", INTEGER),
                        Catalog.field("stoppedAt", INTEGER),
                        Catalog.field("truncated", BOOLEAN),
                        Catalog.field("truncatedBytes", INTEGER),
                        Catalog.field("onError", STRING))
                .withPropertySchema("results", Catalog.arrayOf(row.wireSchema()))
                .withPropertySchema("stoppedAt", nullable(Map.of("type", "integer")));
    }

    private static Map<String, Object> nullable(Map<String, Object> value) {
        return Map.of("oneOf", List.of(value, Map.of("type", "null")));
    }
}
