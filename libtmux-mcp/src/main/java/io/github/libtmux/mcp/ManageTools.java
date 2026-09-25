package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.Argument.flag;
import static io.github.libtmux.mcp.Argument.number;
import static io.github.libtmux.mcp.Argument.paneId;
import static io.github.libtmux.mcp.Argument.required;
import static io.github.libtmux.mcp.Argument.requiredNumber;
import static io.github.libtmux.mcp.Argument.seconds;
import static io.github.libtmux.mcp.OutputSchema.ValueType.BOOLEAN;
import static io.github.libtmux.mcp.OutputSchema.ValueType.INTEGER;
import static io.github.libtmux.mcp.OutputSchema.ValueType.STRING;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_FORMAT;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_LOOKUP;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_STATE;
import static io.github.libtmux.mcp.ToolSpec.OutputClass.TMUX_METADATA;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.NONE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.CHANGE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.OBSERVE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.MANAGE;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The manage toolset: tmux state changes with no client-supplied executable input. */
final class ManageTools {

    private ManageTools() {}

    static void manage(List<ToolSpec> tools) {
        tools.add(Catalog.literalized(
                manageTool(
                        "rename_session",
                        "Rename a session",
                        "Replaces a session's name.",
                        List.of(
                                required("session_id", "The session ID, such as $1."),
                                required("new_name", "The literal new session name.")),
                        Catalog.sinks(Catalog.input("session_id", TMUX_LOOKUP), Catalog.input("new_name", TMUX_FORMAT)),
                        true,
                        Catalog.SESSION_OUTPUT,
                        Operations::renameSession),
                "new_name"));
        tools.add(Catalog.literalized(
                manageTool(
                        "rename_window",
                        "Rename a window",
                        "Replaces a window's name.",
                        List.of(
                                required("window_id", "The window ID, such as @1."),
                                required("new_name", "The literal new window name.")),
                        Catalog.sinks(Catalog.input("window_id", TMUX_LOOKUP), Catalog.input("new_name", TMUX_FORMAT)),
                        true,
                        Catalog.WINDOW_OUTPUT,
                        Operations::renameWindow),
                "new_name"));
        tools.add(manageTool(
                "select_window",
                "Select a window",
                "Makes one window active.",
                List.of(required("window_id", "The window ID, such as @1.")),
                Catalog.sinks(Catalog.input("window_id", TMUX_LOOKUP)),
                true,
                Catalog.WINDOW_OUTPUT,
                Operations::selectWindow));
        tools.add(manageTool(
                "select_pane",
                "Select a pane",
                "Makes one pane active.",
                List.of(paneId()),
                Catalog.sinks(Catalog.input("pane_id", TMUX_LOOKUP)),
                true,
                Catalog.PANE_OUTPUT,
                Operations::selectPane));
        tools.add(manageTool(
                "select_layout",
                "Select a layout",
                "Applies one built-in tmux layout.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        required("layout", "A built-in layout name.")),
                Catalog.sinks(Catalog.input("window_id", TMUX_LOOKUP), Catalog.input("layout", TMUX_STATE)),
                false,
                Catalog.record(Shaping.Changed.class, "note"),
                Shaping::selectLayout));
        tools.add(manageTool(
                "resize_window",
                "Resize a window",
                "Sets a window's width, height or both.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        number("width", "Width in terminal cells; omit to retain it.", 0),
                        number("height", "Height in terminal cells; omit to retain it.", 0)),
                Catalog.sinks(
                        Catalog.input("window_id", TMUX_LOOKUP),
                        Catalog.input("width", TMUX_STATE),
                        Catalog.input("height", TMUX_STATE)),
                true,
                Catalog.WINDOW_OUTPUT,
                Operations::resizeWindow));
        tools.add(manageTool(
                "resize_pane",
                "Resize a pane",
                "Sets a pane's width, height or both.",
                List.of(
                        paneId(),
                        number("width", "Width in terminal cells; omit to retain it.", 0),
                        number("height", "Height in terminal cells; omit to retain it.", 0)),
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP),
                        Catalog.input("width", TMUX_STATE),
                        Catalog.input("height", TMUX_STATE)),
                false,
                Catalog.record(Shaping.Changed.class, "note"),
                Shaping::resizePane));
        tools.add(manageTool(
                "move_window",
                "Move a window",
                "Moves a window to another session, optionally at an index.",
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        required("session_id", "The destination session ID, such as $1."),
                        number("index", "A destination window index; omit for tmux's choice.", -1)),
                Catalog.sinks(
                        Catalog.input("window_id", TMUX_LOOKUP),
                        Catalog.input("session_id", TMUX_LOOKUP),
                        Catalog.input("index", TMUX_STATE)),
                false,
                Catalog.shape(
                        Catalog.field("window_id", STRING),
                        Catalog.field("session_id", STRING),
                        Catalog.field("index", INTEGER)),
                Operations::moveWindow));
        tools.add(manageTool(
                "swap_pane",
                "Swap panes",
                "Swaps the positions of two panes.",
                List.of(paneId(), required("other_pane_id", "The other pane ID, such as %2.")),
                Catalog.sinks(Catalog.input("pane_id", TMUX_LOOKUP), Catalog.input("other_pane_id", TMUX_LOOKUP)),
                false,
                Catalog.shape(Catalog.field("pane_id", STRING), Catalog.field("other_pane_id", STRING)),
                Operations::swapPane));
        tools.add(Catalog.literalized(
                manageTool(
                        "set_pane_title",
                        "Set pane title",
                        "Replaces a pane's literal title.",
                        List.of(paneId(), required("title", "The literal title.")),
                        Catalog.sinks(Catalog.input("pane_id", TMUX_LOOKUP), Catalog.input("title", TMUX_FORMAT)),
                        true,
                        Catalog.PANE_OUTPUT,
                        Operations::setPaneTitle),
                "title"));
        List<Argument> channelWait = List.of(
                required("channel", "A server-wide tmux channel name."),
                seconds("timeout", "Seconds to wait before giving up.", 30),
                flag("drain_first", "Consume a pending signal before waiting.", false));
        tools.add(Catalog.tool(
                "wait_for_channel",
                "Wait for a channel",
                "Waits on tmux's channel state with a bounded timeout.",
                MANAGE,
                NONE,
                Catalog.effects(CHANGE),
                Catalog.outputs(TMUX_METADATA),
                true,
                true,
                channelWait,
                Catalog.sinks(
                        Catalog.input("channel", TMUX_STATE),
                        Catalog.input("timeout", ToolSpec.InputSink.NONE),
                        Catalog.input("drain_first", TMUX_STATE)),
                Catalog.record(Channels.Woke.class, "note"),
                Channels::waitFor));
        tools.add(changeOnlyTool(
                "signal_channel",
                "Signal a channel",
                "Signals one server-wide tmux channel.",
                List.of(required("channel", "The channel name.")),
                Catalog.sinks(Catalog.input("channel", TMUX_STATE)),
                true,
                Catalog.record(Channels.Signalled.class),
                Channels::signal));
        tools.add(changeOnlyTool(
                "set_mouse_enabled",
                "Set mouse handling",
                "Enables or disables tmux mouse handling.",
                List.of(flag("enabled", "Whether mouse handling is enabled.", false)),
                Catalog.sinks(Catalog.input("enabled", TMUX_STATE)),
                false,
                Catalog.shape(Catalog.field("enabled", BOOLEAN)),
                Operations::setMouseEnabled));
        tools.add(changeOnlyTool(
                "set_history_limit",
                "Set history limit",
                "Sets a bounded integer scrollback limit for future panes in a session.",
                List.of(
                        required("session_id", "The session ID, such as $1."),
                        requiredNumber("lines", "The nonnegative retained line count.")),
                Catalog.sinks(Catalog.input("session_id", TMUX_LOOKUP), Catalog.input("lines", TMUX_STATE)),
                false,
                Catalog.shape(Catalog.field("session_id", STRING), Catalog.field("lines", INTEGER)),
                Operations::setHistoryLimit));
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
        return Catalog.tool(
                name,
                title,
                details,
                MANAGE,
                NONE,
                Catalog.effects(OBSERVE, CHANGE),
                Catalog.outputs(TMUX_METADATA),
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
        return Catalog.tool(
                name,
                title,
                details,
                MANAGE,
                NONE,
                Catalog.effects(CHANGE),
                Catalog.outputs(TMUX_METADATA),
                richOutput,
                richOutput,
                arguments,
                sinks,
                output,
                answer);
    }
}
