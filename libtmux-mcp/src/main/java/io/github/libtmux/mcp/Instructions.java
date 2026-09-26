package io.github.libtmux.mcp;

/**
 * What a client tells the model before it has called anything.
 *
 * <p>The only place a rule spanning several tools can be stated. A description says what one tool
 * does; nothing but this can say which of two tools to reach for, or that a word means something
 * else here than it does in the rest of the conversation.
 *
 * <p>Written to be read once and acted on: the mistakes it heads off are the expensive ones, and
 * each costs more than the sentence that prevents it.
 */
final class Instructions {

    private Instructions() {}

    static String forServer(Connection connection) {
        return """
                Drives tmux: Server > Session > Window > Pane. Target by id — %1 pane, @1 window, \
                $1 session; ids survive, positions do not.

                Panes, windows, sessions, and a bare "the terminal", "this shell", "split", "send \
                keys", "scrollback". Do NOT use them for browser tabs, editor splits (VS Code, \
                Neovim), desktop windows, Jupyter cells, or login sessions; ask first on an \
                ambiguous bare "window" or "session".

                get_server_info identifies the server. list_panes gives stable ids and marks this \
                process's pane. Teardown guards that pane; pane input also refuses attended panes \
                (a terminal client is currently displaying them), a human-owned mode, and any \
                non-taking member of a synchronized window.

                WAIT, DO NOT POLL
                Wrote it: run_shell_command sends, waits, returns output and exit status in one \
                call — never poll capture_pane to guess. Did not write it: wait_for_text, always \
                with 'stop' set to the failure text. Can compose a signal: wait_for_channel blocks \
                in tmux. Watching over turns: capture_since's cursor charges only for new lines.

                list_panes knows command, path, size, not what a pane shows: use capture_pane, \
                capture_since or search_panes. It has no filter; scan 'command'.

                Reads are capped and say what they dropped. Batch reads or key sends with \
                call_read_tools_batch or send_keys_batch. snapshot_pane: metadata and content.

                ABSENT ON PURPOSE
                No hook writing: a hook outlives this conversation; use your tmux config. No \
                environment writes: later shells, a user's too, inherit them; use env NAME=value. \
                No buffer reading by default: buffers may hold what a user copied.

                tmux://capabilities: the frozen tool surface and socket.
                """ + ending(connection);
    }

    /**
     * What is missing, said plainly.
     *
     * <p>A model that cannot see a tool cannot tell an operator's choice from a gap in the server,
     * and will otherwise spend a turn looking for a way to do what it has been refused.
     */
    private static String ending(Connection connection) {
        var socket = connection.surface().socketReport(connection.server());
        String toolsets = String.join(",", connection.surface().toolsetNames());
        return "\nCAPABILITIES\nOperating on selected socket " + socket.get("selector")
                + " (" + socket.get("selectionProvenance") + "). Enabled toolsets: "
                + (toolsets.isEmpty() ? "none" : toolsets) + ". Execute tools run with the tmux user's authority. "
                + "Tool filtering shapes this advertised interface; it is not authorization or an OS sandbox.\n";
    }
}
