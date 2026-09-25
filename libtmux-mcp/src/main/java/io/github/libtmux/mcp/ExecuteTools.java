package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.Argument.boundedObjects;
import static io.github.libtmux.mcp.Argument.flag;
import static io.github.libtmux.mcp.Argument.number;
import static io.github.libtmux.mcp.Argument.optional;
import static io.github.libtmux.mcp.Argument.paneId;
import static io.github.libtmux.mcp.Argument.required;
import static io.github.libtmux.mcp.Argument.seconds;
import static io.github.libtmux.mcp.Argument.strings;
import static io.github.libtmux.mcp.OutputSchema.ValueType.ARRAY;
import static io.github.libtmux.mcp.OutputSchema.ValueType.BOOLEAN;
import static io.github.libtmux.mcp.OutputSchema.ValueType.INTEGER;
import static io.github.libtmux.mcp.OutputSchema.ValueType.STRING;
import static io.github.libtmux.mcp.ToolSpec.InputSink.SHELL_COMMAND;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_FORMAT;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_LOOKUP;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_STATE;
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

import java.util.List;
import java.util.Map;

/** The execute toolset: tools that start or drive a pane's process. */
final class ExecuteTools {

    private ExecuteTools() {}

    static void execute(List<ToolSpec> tools) {
        tools.add(Catalog.literalized(
                Catalog.tool(
                        "create_session",
                        "Create a session",
                        "Creates a detached session whose first pane runs the configured process.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        Catalog.effects(OBSERVE, CHANGE),
                        Catalog.outputs(TMUX_METADATA),
                        true,
                        true,
                        List.of(
                                optional("session_name", "A literal session name."),
                                optional("window_name", "A literal first-window name."),
                                optional("start_directory", "An absolute literal start directory."),
                                number("width", "Initial width; supply with height.", -1),
                                number("height", "Initial height; supply with width.", -1)),
                        Catalog.sinks(
                                Catalog.input("session_name", TMUX_FORMAT),
                                Catalog.input("window_name", TMUX_FORMAT),
                                Catalog.input("start_directory", TMUX_FORMAT),
                                Catalog.input("width", TMUX_STATE),
                                Catalog.input("height", TMUX_STATE)),
                        Catalog.SESSION_OUTPUT,
                        Operations::createSession),
                "session_name",
                "window_name",
                "start_directory"));
        tools.add(Catalog.literalized(
                Catalog.tool(
                        "create_window",
                        "Create a window",
                        "Creates a window whose first pane runs the configured process.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        Catalog.effects(OBSERVE, CHANGE),
                        Catalog.outputs(TMUX_METADATA),
                        true,
                        true,
                        List.of(
                                required("session_id", "The session ID, such as $1."),
                                optional("window_name", "A literal window name."),
                                optional("start_directory", "An absolute literal start directory."),
                                flag("attach", "Make the new window active.", false),
                                optional("direction", "before or after.")),
                        Catalog.sinks(
                                Catalog.input("session_id", TMUX_LOOKUP),
                                Catalog.input("window_name", TMUX_FORMAT),
                                Catalog.input("start_directory", TMUX_FORMAT),
                                Catalog.input("attach", TMUX_STATE),
                                Catalog.input("direction", TMUX_STATE)),
                        Catalog.WINDOW_OUTPUT,
                        Operations::createWindow),
                "window_name",
                "start_directory"));
        tools.add(Catalog.literalized(
                Catalog.tool(
                        "split_window",
                        "Split a window",
                        "Creates a pane whose configured process starts after the split.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        Catalog.effects(OBSERVE, CHANGE),
                        Catalog.outputs(TMUX_METADATA),
                        true,
                        true,
                        List.of(
                                paneId(),
                                optional("direction", "below, above, left or right."),
                                number("percent", "Share of the split occupied by the new pane.", 50),
                                optional("start_directory", "An absolute literal start directory.")),
                        Catalog.sinks(
                                Catalog.input("pane_id", TMUX_LOOKUP),
                                Catalog.input("direction", TMUX_STATE),
                                Catalog.input("percent", TMUX_STATE),
                                Catalog.input("start_directory", TMUX_FORMAT)),
                        Catalog.PANE_OUTPUT,
                        Operations::splitWindow),
                "start_directory"));
        tools.add(Catalog.literalized(
                Catalog.tool(
                        "respawn_pane",
                        "Respawn a pane",
                        "Kills the pane's current process and starts its configured process again.",
                        EXECUTE,
                        CONFIGURED_PROCESS,
                        Catalog.effects(OBSERVE, CHANGE, DELETE),
                        Catalog.outputs(TMUX_METADATA),
                        false,
                        false,
                        List.of(paneId(), optional("start_directory", "An absolute literal start directory.")),
                        Catalog.sinks(
                                Catalog.input("pane_id", TMUX_LOOKUP), Catalog.input("start_directory", TMUX_FORMAT)),
                        Catalog.shape(Catalog.field("pane_id", STRING), Catalog.field("restarted", BOOLEAN)),
                        Operations::respawnPane),
                "start_directory"));

        List<Argument> run = List.of(
                paneId(),
                required("command", "The shell command, run in the pane's interactive shell."),
                seconds("timeout", "Seconds to wait before giving up.", 30),
                number("max_lines", "Maximum output lines, keeping the newest.", Trim.DEFAULT_LINES),
                flag("suppress_history", "Best-effort persistent history suppression.", true));
        tools.add(Catalog.tool(
                "run_shell_command",
                "Run a shell command",
                "Runs one authored command in a trusted pane shell and waits for singular framed output and "
                        + "completion. Its two preflights refuse caller or attended panes and an effective cohort "
                        + "larger than one. "
                        + "Pre-existing exact-client-path, trap, eval, or exit functions are outside the supported "
                        + "boundary; marker display-message commands honor the trusted server's command aliases "
                        + "and hooks.",
                EXECUTE,
                PANE_COMMAND,
                Catalog.effects(OBSERVE, CHANGE),
                Catalog.outputs(TERMINAL_CONTENT, TMUX_METADATA),
                true,
                true,
                run,
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP),
                        Catalog.input("command", ToolSpec.InputSink.PANE_INPUT, SHELL_COMMAND),
                        Catalog.input("timeout", ToolSpec.InputSink.NONE),
                        Catalog.input("max_lines", ToolSpec.InputSink.NONE),
                        Catalog.input("suppress_history", ToolSpec.InputSink.NONE)),
                Catalog.record(RunningCommands.Ran.class, "exit_status", "note"),
                RunningCommands::run));

        List<Argument> keys = List.of(
                paneId(),
                strings("keys", "The key names or literal strings to send."),
                flag("literal", "Send strings literally instead of as key names.", false),
                flag(
                        "enter",
                        "Press Enter afterward, as a real keypress. Unlike a key named \"Enter\" sent under "
                                + "literal:true, which types the four letters, this always submits.",
                        false));
        tools.add(Catalog.tool(
                "send_keys",
                "Send keys",
                "Sends input to the target's configured effective synchronized cohort without waiting for output. "
                        + "Every configured member must be live, nonmodal, and neither caller nor attended. Reports "
                        + "configured pane ids observed before dispatch, not delivery receipts. wait_for_text"
                        + " discounts recognized input; wait for the prompt before typing into a cold shell.",
                EXECUTE,
                PANE_INPUT,
                Catalog.effects(OBSERVE, CHANGE),
                Catalog.outputs(TMUX_METADATA),
                false,
                false,
                keys,
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP),
                        Catalog.input("keys", ToolSpec.InputSink.PANE_INPUT),
                        Catalog.input("literal", ToolSpec.InputSink.NONE),
                        Catalog.input("enter", ToolSpec.InputSink.PANE_INPUT)),
                Catalog.record(Typing.Sent.class, "note"),
                Typing::sendKeys));
        tools.add(Catalog.tool(
                "send_keys_batch",
                "Send keys in a batch",
                "Sends up to sixty-four ordered pane-input operations, resolving and guarding the configured "
                        + "effective cohort separately for each ordered operation. A later policy or dispatch "
                        + "failure retains observed membership.",
                EXECUTE,
                PANE_INPUT,
                Catalog.effects(OBSERVE, CHANGE),
                Catalog.outputs(TMUX_METADATA),
                true,
                true,
                List.of(
                        boundedObjects(
                                "operations", "Objects with pane_id, keys and optional literal and enter fields.", 64),
                        optional("onError", "stop or continue; defaults to stop.")),
                Catalog.sinks(
                        Catalog.input("operations", TMUX_LOOKUP, ToolSpec.InputSink.PANE_INPUT),
                        Catalog.input("onError", ToolSpec.InputSink.NONE)),
                sendBatchOutput(),
                Operations::sendKeysBatch));
        tools.add(Catalog.tool(
                "paste_text",
                "Paste text",
                "Pastes one literal text block into one target pane through an ephemeral buffer; paste-buffer "
                        + "input does not fan out to synchronized peers. The target cannot be caller or attended.",
                EXECUTE,
                PANE_INPUT,
                Catalog.effects(OBSERVE, CHANGE),
                Catalog.outputs(TMUX_METADATA),
                false,
                false,
                List.of(
                        paneId(),
                        required("text", "The literal text to paste."),
                        flag("enter", "Append a newline that submits the text.", false)),
                Catalog.sinks(
                        Catalog.input("pane_id", TMUX_LOOKUP),
                        Catalog.input("text", ToolSpec.InputSink.PANE_INPUT),
                        Catalog.input("enter", ToolSpec.InputSink.PANE_INPUT)),
                Catalog.record(Typing.Pasted.class, "note"),
                Typing::pasteText));
        tools.add(amplifying(Catalog.tool(
                "set_synchronize_panes",
                "Set synchronized panes",
                "When enabled, sets the inherited window default; pane-level overrides determine each pane's "
                        + "effective synchronized value.",
                EXECUTE,
                NONE,
                Catalog.effects(CHANGE),
                Catalog.outputs(TMUX_METADATA),
                false,
                false,
                List.of(
                        required("window_id", "The window ID, such as @1."),
                        flag("enabled", "Whether pane input is synchronized.", false)),
                Catalog.sinks(Catalog.input("window_id", TMUX_LOOKUP), Catalog.input("enabled", TMUX_STATE)),
                Catalog.shape(Catalog.field("window_id", STRING), Catalog.field("enabled", BOOLEAN)),
                Operations::setSynchronizePanes)));
    }

    private static ToolSpec amplifying(ToolSpec tool) {
        return tool.amplifyingFutureInput();
    }

    private static OutputSchema sendBatchOutput() {
        OutputSchema row = Catalog.shape(
                        Catalog.field("index", INTEGER),
                        Catalog.field("pane_id", STRING),
                        Catalog.field("resolved_pane_ids", ARRAY),
                        Catalog.field("success", BOOLEAN),
                        Catalog.field("error", STRING))
                .withOptionalFields("error")
                .withPropertySchema("resolved_pane_ids", Catalog.arrayOf(Map.of("type", "string")));
        return Catalog.shape(Catalog.field("results", ARRAY), Catalog.field("completed", INTEGER))
                .withPropertySchema("results", Catalog.arrayOf(row.wireSchema()));
    }
}
