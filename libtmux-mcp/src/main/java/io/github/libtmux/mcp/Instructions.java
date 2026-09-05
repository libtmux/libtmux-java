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
                Drives tmux: a terminal multiplexer holding Server > Session > Window > Pane.
                Target everything by id — %1 a pane, @1 a window, $1 a session. Ids survive; \
                positions move as neighbours come and go.

                WHEN TO USE ME
                Use these tools for tmux panes, windows and sessions, and for a bare "the terminal", \
                "this shell", "split", "send keys", "scrollback". A %, @ or $ id is unambiguous.
                Do NOT use them for browser tabs, editor splits (VS Code, Neovim), desktop windows, \
                Jupyter cells, or login sessions. On a bare "window" or "session" with no terminal in \
                sight, ask which is meant before acting.

                START HERE
                get_server_info identifies the pinned server. list_panes returns stable pane IDs and \
                marks this process's pane when it runs inside the selected server. Direct teardown \
                tools guard that pane. This process cannot address objects outside its selected socket.

                WAIT, DO NOT POLL
                A command you wrote: run_shell_command. It sends, waits, and returns output with an exit status \
                in one call. Never send a command and then call capture_pane repeatedly to guess \
                whether it finished.
                Output you did not start: wait_for_text, always with 'stop' set to the failure \
                text — without it a run that fails is waited on until the deadline.
                Something you can compose a signal into: wait_for_channel. It blocks inside tmux \
                and infers nothing from the screen.
                Watching over several turns: capture_since with the cursor it returns, so you pay \
                for new lines rather than the whole screen again.
                Every wait is bounded and says the ceiling it enforced. A wait that ends without what \
                you wanted is a cheap retry, not a failure.

                METADATA IS NOT CONTENT
                list_panes and friends read what tmux knows about a pane — its command, its path, \
                its size. What a pane is SHOWING comes from capture_pane, capture_since or \
                search_panes. "Which pane mentions the error" is a search, not a listing.

                READING COSTS CONTEXT
                Reads are capped and say when they dropped anything; raise 'max_lines' deliberately \
                rather than by habit. Prefer list_panes over reading every pane's content.

                CAPABILITY DISCLOSURE
                tmux://capabilities reports this process's frozen effective tool surface and selected \
                socket. It is the only MCP resource exposed by this server.
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
