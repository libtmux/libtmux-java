package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.Argument.flag;
import static io.github.libtmux.mcp.Argument.paneId;
import static io.github.libtmux.mcp.Argument.required;
import static io.github.libtmux.mcp.OutputSchema.ValueType.BOOLEAN;
import static io.github.libtmux.mcp.OutputSchema.ValueType.STRING;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_LOOKUP;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.TMUX_METADATA;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.NONE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.DELETE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.OBSERVE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.TEARDOWN;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The teardown toolset: tools that delete tmux state. */
final class TeardownTools {

    private TeardownTools() {}

    static void teardown(List<ToolSpec> tools) {
        tools.add(deleteOnlyTool(
                "clear_pane_scrollback",
                "Clear pane scrollback",
                "Deletes retained scrollback from one pane.",
                List.of(paneId()),
                Catalog.sinks(Catalog.input("pane_id", TMUX_LOOKUP)),
                Catalog.shape(Catalog.field("pane_id", STRING), Catalog.field("cleared", BOOLEAN)),
                Operations::clearPaneScrollback));
        tools.add(teardownTool(
                "kill_pane",
                "Kill a pane",
                "Deletes one pane and ends its process.",
                killArguments("pane_id", "The pane ID, such as %1."),
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP), Catalog.input("confirm_self", ToolSpec.InputSink.NONE)),
                Catalog.record(Shaping.Ended.class, "note"),
                Operations::killPane));
        tools.add(teardownTool(
                "kill_window",
                "Kill a window",
                "Deletes one window and every pane in it.",
                killArguments("window_id", "The window ID, such as @1."),
                Catalog.sinks(
                        Catalog.input("window_id", TMUX_LOOKUP),
                        Catalog.input("confirm_self", ToolSpec.InputSink.NONE)),
                Catalog.record(Shaping.Ended.class, "note"),
                Operations::killWindow));
        tools.add(teardownTool(
                "kill_session",
                "Kill a session",
                "Deletes one session and every window and pane in it.",
                killArguments("session_id", "The session ID, such as $1."),
                Catalog.sinks(
                        Catalog.input("session_id", TMUX_LOOKUP),
                        Catalog.input("confirm_self", ToolSpec.InputSink.NONE)),
                Catalog.record(Shaping.Ended.class, "note"),
                Operations::killSession));
    }

    private static List<Argument> killArguments(String name, String description) {
        return List.of(
                required(name, description),
                flag("confirm_self", "Permit ending the pane this MCP process runs in.", false));
    }

    private static ToolSpec teardownTool(
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
                TEARDOWN,
                NONE,
                Catalog.effects(OBSERVE, DELETE),
                Catalog.outputs(TMUX_METADATA),
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
        return Catalog.tool(
                name,
                title,
                details,
                TEARDOWN,
                NONE,
                Catalog.effects(DELETE),
                Catalog.outputs(TMUX_METADATA),
                false,
                false,
                arguments,
                sinks,
                output,
                answer);
    }
}
