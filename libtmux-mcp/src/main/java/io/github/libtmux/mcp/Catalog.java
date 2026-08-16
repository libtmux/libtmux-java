package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.Argument.flag;
import static io.github.libtmux.mcp.Argument.number;
import static io.github.libtmux.mcp.Argument.optional;
import static io.github.libtmux.mcp.Argument.paneId;
import static io.github.libtmux.mcp.Argument.required;
import static io.github.libtmux.mcp.Argument.seconds;
import static io.github.libtmux.mcp.Argument.strings;

import io.github.libtmux.jackson.FilterJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tool this server can offer, in the order a model meets them.
 *
 * <p>Declared in one list so the surface can be read at a glance and so nothing can be added
 * without stating what it may destroy. What a launcher actually serves is this list narrowed to a
 * {@link Safety} ceiling.
 *
 * <p>The descriptions are written for a model rather than a person: each says what the tool is for
 * and, where a cheaper tool exists, points at it. That is the only documentation a model gets.
 */
final class Catalog {

    /** The filter document shown to a model, and the only one it is given to copy. */
    static final String EXAMPLE_FILTER = "{\"schema\":\"" + FilterJson.SCHEMA + "\",\"model\":\"pane\","
            + "\"expr\":{\"node\":\"compare\",\"field\":\"pane_current_command\","
            + "\"op\":\"starts_with\",\"value\":\"nvim\"}}";

    private Catalog() {}

    static List<ToolSpec> tools() {
        List<ToolSpec> tools = new ArrayList<>();
        discovery(tools);
        reading(tools);
        waiting(tools);
        typing(tools);
        shaping(tools);
        settings(tools);
        ending(tools);
        return List.copyOf(tools);
    }

    // ------------------------------------------------------------------ what is there

    private static void discovery(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_whoami",
                "Which tmux, and which pane is mine",
                "Describes the tmux server this connection acts on, and names the pane this MCP server is "
                        + "itself running in when there is one. Call this first in an unfamiliar session: it is "
                        + "the only way to learn which pane belongs to this conversation, and that pane is the "
                        + "one never to kill or type into.",
                Safety.READONLY,
                List.of(),
                call -> Listings.whoami(call.server(), call.caller(), call.ceiling())));

        tools.add(ToolSpec.of(
                "tmux_list_servers",
                "List tmux servers",
                "Lists every tmux server this user has running, by socket. Use it when the sessions you "
                        + "expected are not on this server: tmux keeps entirely separate servers per socket, and "
                        + "they cannot see each other.",
                Safety.READONLY,
                List.of(),
                call -> Listings.servers(call.server(), call.server().config().binary())));

        tools.add(ToolSpec.of(
                "tmux_list_sessions",
                "List sessions",
                "Lists sessions on this server with the windows in each.",
                Safety.READONLY,
                List.of(),
                call -> Listings.sessions(call.server())));

        tools.add(ToolSpec.of(
                "tmux_list_windows",
                "List windows",
                "Lists windows with the id other tools take, optionally only those in one session.",
                Safety.READONLY,
                List.of(optional("session", "Only windows in this session. Omit for every window on the server.")),
                Listings::windows));

        tools.add(ToolSpec.of(
                "tmux_list_panes",
                "List panes",
                "Lists panes with the id every other tool takes as a target, what is running in each, and "
                        + "where. Optionally narrowed by a filter document. This reads metadata, not screen "
                        + "contents: to find a pane by what it is showing, use tmux_search_panes.",
                Safety.READONLY,
                List.of(new Argument(
                        "filter",
                        "object",
                        "A " + FilterJson.SCHEMA + " document over the pane model, for example " + EXAMPLE_FILTER
                                + ". Field names are tmux's own format names. Omit it to list every pane.",
                        false,
                        null)),
                Listings::panes));

        tools.add(ToolSpec.of(
                "tmux_list_clients",
                "List attached clients",
                "Lists the terminals attached to this server. Use it to find out whether a person is "
                        + "watching a session before changing what it is showing.",
                Safety.READONLY,
                List.of(),
                Listings::clients));
    }

    // ------------------------------------------------------------------ what panes show

    private static void reading(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_capture_pane",
                "Read a pane",
                "Returns what a pane is showing, newest last, together with a cursor. To watch the same "
                        + "pane again, pass that cursor to tmux_capture_since instead of calling this repeatedly "
                        + "— this returns the whole screen every time.",
                Safety.READONLY,
                List.of(
                        paneId(),
                        flag("history", "Include the pane's scrollback, not only the visible screen.", false),
                        number("max_lines", "How many lines at most, keeping the newest.", Trim.DEFAULT_LINES)),
                Reading::capture));

        tools.add(ToolSpec.of(
                "tmux_capture_since",
                "Read what is new in a pane",
                "Returns only the lines a pane has produced since a cursor, and a new cursor. This is how "
                        + "to watch something without paying for it repeatedly: the tenth look at a build log "
                        + "costs the few lines it added, not the nine screens already read. Omit the cursor to "
                        + "start from what the pane shows now.",
                Safety.READONLY,
                List.of(
                        paneId(),
                        optional("cursor", "The cursor from a previous call on this pane. Omit to start here."),
                        number("max_lines", "How many lines at most, keeping the newest.", Trim.DEFAULT_LINES)),
                Reading::since));

        tools.add(ToolSpec.of(
                "tmux_search_panes",
                "Find panes by what they show",
                "Searches what every pane is currently showing and returns the panes that match. Use it to "
                        + "answer \"which pane has the server in it\". Searches the visible screen, not "
                        + "scrollback, so text that has scrolled away is not found.",
                Safety.READONLY,
                List.of(
                        required("pattern", "The text to look for."),
                        flag("regex", "Treat the pattern as a regular expression rather than plain text.", false),
                        number("max_matches_per_pane", "How many matching lines to keep from each pane.", 5),
                        number("max_lines", "How many matches at most, across all panes.", Trim.DEFAULT_LINES)),
                Reading::search));
    }

    // ------------------------------------------------------------------ waiting

    private static void waiting(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_run",
                "Run a command and wait for it",
                "Runs a shell command in a pane, waits for it to finish, and returns its output and exit "
                        + "status in one call. Use this whenever you wrote the command yourself. Do not send a "
                        + "command and then poll tmux_capture_pane to guess whether it finished: that costs a "
                        + "call per look and still cannot tell a finished command from a stalled one. The "
                        + "command runs in a subshell of the pane's shell, so it sees that shell's environment "
                        + "but a 'cd' or an export in it does not outlive the call — and neither does an 'exit'.",
                Safety.MUTATING,
                List.of(
                        paneId(),
                        required("command", "The shell command, run in the pane's own interactive shell."),
                        seconds(
                                "timeout",
                                "Seconds to wait before giving up and reporting what it printed so far.",
                                30),
                        number(
                                "max_lines",
                                "How many lines of output at most, keeping the newest.",
                                Trim.DEFAULT_LINES),
                        flag(
                                "suppress_history",
                                "Prefix the line with a space so a shell configured to ignore such lines keeps it "
                                        + "out of its history. Best-effort: a shell not configured that way records it.",
                                true)),
                RunningCommands::run));

        tools.add(ToolSpec.of(
                "tmux_wait_for_text",
                "Wait for text to appear in a pane",
                "Waits until text appears in a pane you did not start — a dev server, a daemon, a build "
                        + "someone else launched. Only output that arrives after this call counts, so text "
                        + "already on screen does not satisfy it. Always pass 'stop' with the failure text when "
                        + "there is one: without it a run that fails is waited on until the deadline. If you "
                        + "wrote the command, use tmux_run instead.",
                Safety.READONLY,
                List.of(
                        paneId(),
                        strings(
                                "patterns",
                                "Text to wait for; any one of them ends the wait. Omit to wait for "
                                        + "any new output at all."),
                        strings(
                                "stop",
                                "Text that means it has failed. Matching one ends the wait at once and "
                                        + "reports STOPPED."),
                        flag("regex", "Treat patterns and stops as regular expressions rather than plain text.", false),
                        seconds("timeout", "Seconds to wait before giving up.", 30),
                        optional("cursor", "Carry on from a cursor a previous call returned."),
                        number("max_lines", "How many lines of what it saw to return.", Trim.DEFAULT_LINES)),
                WaitingForText::waitFor));

        tools.add(ToolSpec.of(
                "tmux_wait_for_channel",
                "Wait on a tmux channel",
                "Blocks until something signals a tmux channel. This is the only wait that infers nothing "
                        + "from the screen: compose a command as 'mycommand; tmux wait-for -S mychannel' with "
                        + "tmux_send_keys, then wait here. The answer says why the wait ended, because tmux "
                        + "reports a server that died under a waiter as a successful wake.",
                Safety.READONLY,
                List.of(
                        required("channel", "The channel name, which everything on this server shares."),
                        seconds("timeout", "Seconds to wait before giving up.", 30),
                        flag(
                                "drain_first",
                                "Consume a signal left over from before this call, so the wait starts from a "
                                        + "known state.",
                                false)),
                Channels::waitFor));

        tools.add(ToolSpec.of(
                "tmux_signal_channel",
                "Signal a tmux channel",
                "Wakes whatever is waiting on a tmux channel. A signal sent when nothing is waiting is "
                        + "remembered and satisfies the next wait.",
                Safety.MUTATING,
                List.of(required("channel", "The channel name.")),
                Channels::signal));

        tools.add(ToolSpec.of(
                "tmux_drain_channel",
                "Clear a stale channel signal",
                "Consumes a signal already waiting on a channel, so a leftover one cannot satisfy a wait "
                        + "that has not happened yet.",
                Safety.MUTATING,
                List.of(required("channel", "The channel name.")),
                Channels::drain));
    }

    // ------------------------------------------------------------------ input

    private static void typing(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_send_keys",
                "Send keys to a pane",
                "Sends keypresses by tmux's names for them — 'C-c' to interrupt, 'q' to quit a pager, "
                        + "'Up' for the previous command. This is for controlling a program, not for running "
                        + "commands: a command you wrote belongs in tmux_run, which waits for it.",
                Safety.MUTATING,
                List.of(
                        paneId(),
                        strings("keys", "The keys, as tmux names them, for example [\"C-c\"] or [\"y\", \"Enter\"]."),
                        flag("literal", "Send the strings as text rather than looking them up as key names.", false)),
                Typing::sendKeys));

        tools.add(ToolSpec.of(
                "tmux_paste_text",
                "Paste text into a pane",
                "Puts text into a pane as a paste rather than as keystrokes, so brackets, newlines and "
                        + "anything that spells a key name arrive as the characters they are. Use it for an "
                        + "editor, a REPL, or a here-document.",
                Safety.MUTATING,
                List.of(
                        paneId(),
                        required("text", "The text to paste."),
                        flag("enter", "End the paste with a newline, submitting it.", false)),
                Typing::pasteText));
    }

    // ------------------------------------------------------------------ structure

    private static void shaping(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_new_session",
                "Create a session",
                "Creates a detached session and returns its first pane's id.",
                Safety.MUTATING,
                List.of(
                        required("name", "The session name."),
                        optional("path", "The directory its first pane starts in."),
                        optional("command", "A command to run in it instead of a shell.")),
                Shaping::newSession));

        tools.add(ToolSpec.of(
                "tmux_new_window",
                "Create a window",
                "Creates a window in a session without switching to it, and returns its first pane's id.",
                Safety.MUTATING,
                List.of(
                        required("session", "The session to create it in."),
                        optional("name", "The window name. Omit to let tmux name it after what runs in it."),
                        optional("path", "The directory it starts in."),
                        optional("command", "A command to run in it instead of a shell.")),
                Shaping::newWindow));

        tools.add(ToolSpec.of(
                "tmux_split_pane",
                "Split a pane",
                "Splits a pane in two and returns the id of the new one. The direction says where the new "
                        + "pane goes.",
                Safety.MUTATING,
                List.of(
                        paneId(),
                        optional("direction", "Where the new pane goes: below, above, left or right."),
                        number("percent", "How much of the space the new pane takes, 1 to 99.", 50),
                        optional("path", "The directory it starts in."),
                        optional("command", "A command to run in it instead of a shell.")),
                Shaping::splitPane));

        tools.add(ToolSpec.of(
                "tmux_apply_workspace",
                "Build a session from a description",
                "Builds a whole session — windows, panes, layouts and the commands to start in them — from "
                        + "one YAML document in the shape tmuxp uses. One call instead of a dozen, and a "
                        + "description tmux would refuse is refused before anything is half-built. Example:\n"
                        + Workspaces.example(),
                Safety.MUTATING,
                List.of(required("workspace", "The YAML document describing the session.")),
                Workspaces::apply));

        tools.add(ToolSpec.of(
                "tmux_rename",
                "Rename a window or session",
                "Renames a window given its @id, or a session given its name.",
                Safety.MUTATING,
                List.of(
                        required("target", "A window id such as @1, or a session name."),
                        required("name", "The new name.")),
                Shaping::rename));

        tools.add(ToolSpec.of(
                "tmux_select",
                "Bring a pane or window to the front",
                "Makes a pane or window the active one, which is what a person attached to the session then "
                        + "sees. Not needed to read or act on something: every other tool takes an id and works "
                        + "whether or not the target is active.",
                Safety.MUTATING,
                List.of(required("target", "A pane id such as %1, or a window id such as @1.")),
                Shaping::select));

        tools.add(ToolSpec.of(
                "tmux_select_layout",
                "Rearrange a window's panes",
                "Applies one of tmux's layouts to a window: " + String.join(", ", Shaping.layoutNames()) + ".",
                Safety.MUTATING,
                List.of(required("window_id", "The window id, such as @1."), required("layout", "The layout name.")),
                Shaping::selectLayout));

        tools.add(ToolSpec.of(
                "tmux_resize_pane",
                "Resize a pane",
                "Sets a pane's size in cells. A pane cannot grow past its window, and its neighbours have to "
                        + "give up what it takes, so the size that results may not be the one asked for — the "
                        + "answer says what it actually became.",
                Safety.MUTATING,
                List.of(
                        paneId(),
                        number("width", "Width in cells. Omit to leave it.", 0),
                        number("height", "Height in cells. Omit to leave it.", 0)),
                Shaping::resizePane));
    }

    // ------------------------------------------------------------------ configuration

    private static void settings(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_show_options",
                "Read tmux options",
                "Reads a set of tmux options. tmux keeps four sets and lets a lower one override the one "
                        + "above, so say which scope you mean: global, server, session, window or pane.",
                Safety.READONLY,
                List.of(
                        optional("scope", "global, server, session, window or pane. Defaults to global."),
                        optional("target", "Which session, window or pane, when the scope is one of those."),
                        flag(
                                "effective",
                                "Include values inherited from a wider scope, not only those set here.",
                                false)),
                Settings::showOptions));

        tools.add(ToolSpec.of(
                "tmux_set_option",
                "Set a tmux option",
                "Sets one tmux option in one scope. Setting it globally changes it for everything that has "
                        + "not overridden it, including panes a person is using, so prefer the narrowest scope "
                        + "that does what you need.",
                Safety.MUTATING,
                List.of(
                        required("name", "The option name."),
                        required("value", "The value to set."),
                        optional("scope", "global, server, session, window or pane. Defaults to global."),
                        optional("target", "Which session, window or pane, when the scope is one of those.")),
                Settings::setOption));

        tools.add(ToolSpec.of(
                "tmux_show_hooks",
                "Read tmux hooks",
                "Reads the hooks set in a scope. Read-only: a hook set over MCP would be gone when this "
                        + "server restarts, so one that should last belongs in a tmux config file.",
                Safety.READONLY,
                List.of(
                        optional("scope", "global, server, session, window or pane. Defaults to global."),
                        optional("target", "Which session, window or pane, when the scope is one of those.")),
                Settings::showHooks));

        tools.add(ToolSpec.of(
                "tmux_show_environment",
                "Read the tmux environment",
                "Reads the environment tmux passes to programs it starts, globally or for one session. This "
                        + "is what a new pane will inherit, not what a running program currently has.",
                Safety.READONLY,
                List.of(optional("session", "The session to read. Omit for the global environment.")),
                Settings::environment));
    }

    // ------------------------------------------------------------------ ending things

    private static void ending(List<ToolSpec> tools) {
        tools.add(ToolSpec.of(
                "tmux_kill",
                "Destroy a pane, window, session or the server",
                "Ends something and everything running in it. This cannot be undone: the processes inside "
                        + "are killed, and unsaved work in them is gone. Refuses to end the pane this "
                        + "conversation is running through unless confirm_self is set — call tmux_whoami to see "
                        + "which pane that is.",
                Safety.DESTRUCTIVE,
                List.of(
                        required(
                                "target",
                                "A pane id such as %1, a window id such as @1, a session name, or the word "
                                        + "'server' to end every session on it."),
                        flag(
                                "confirm_self",
                                "Go ahead even though the target holds the pane this MCP server runs in.",
                                false)),
                Shaping::kill));
    }

    /** The tools a server at this ceiling offers, keyed by name. */
    static Map<String, ToolSpec> offered(Safety ceiling) {
        Map<String, ToolSpec> offered = new LinkedHashMap<>();
        for (ToolSpec tool : tools()) {
            if (ceiling.allows(tool.safety())) {
                offered.put(tool.name(), tool);
            }
        }
        return offered;
    }
}
