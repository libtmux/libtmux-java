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

    static String forServer(Safety ceiling, boolean watching) {
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
                tmux_whoami says which server this is and, when the client launched me from inside \
                tmux, which pane this conversation is coming through. That pane is the one never to \
                kill or type into. tmux_list_servers finds other tmux servers when the sessions you \
                expected are missing — separate sockets cannot see each other.

                WAIT, DO NOT POLL
                A command you wrote: tmux_run. It sends, waits, and returns output with an exit status \
                in one call. Never send a command and then call tmux_capture_pane repeatedly to guess \
                whether it finished.
                Output you did not start: tmux_wait_for_text, always with 'stop' set to the failure \
                text — without it a run that fails is waited on until the deadline.
                Something you can compose a signal into: tmux_wait_for_channel. It blocks inside tmux \
                and infers nothing from the screen.
                Watching over several turns: tmux_capture_since with the cursor it returns, so you pay \
                for new lines rather than the whole screen again.
                Every wait is bounded and says the ceiling it enforced. A wait that ends without what \
                you wanted is a cheap retry, not a failure.

                METADATA IS NOT CONTENT
                tmux_list_panes and friends read what tmux knows about a pane — its command, its path, \
                its size. What a pane is SHOWING comes from tmux_capture_pane, tmux_capture_since or \
                tmux_search_panes. "Which pane mentions the error" is a search, not a listing.

                READING COSTS CONTEXT
                Reads are capped and say when they dropped anything; raise 'max_lines' deliberately \
                rather than by habit. Prefer a filter on tmux_list_panes over reading every pane.

                RESOURCES AND RECIPES
                tmux://... resources expose the same state for a client to attach without spending a \
                tool call. The prompts here are worked recipes for the common jobs.
                """ + watching(watching) + ending(ceiling);
    }

    /**
     * Said only when it is true. A model told it will be notified, that then is not, waits for
     * something that never comes — which is worse than knowing it has to ask.
     */
    private static String watching(boolean watching) {
        return watching
                ? "\nPUSHED UPDATES\nThis server watches tmux and sends notifications/resources/updated "
                        + "when a pane produces output or the shape of the server changes. Subscribe to "
                        + "tmux://panes/{pane_id}/content rather than re-reading a pane to see whether "
                        + "anything happened.\n"
                : "";
    }

    /**
     * What is missing, said plainly.
     *
     * <p>A model that cannot see a tool cannot tell an operator's choice from a gap in the server,
     * and will otherwise spend a turn looking for a way to do what it has been refused.
     */
    private static String ending(Safety ceiling) {
        return switch (ceiling) {
            case READONLY ->
                "\nSAFETY\nThis server is read-only. Nothing here changes tmux: no sending "
                        + "keys, no creating or killing. Ask the operator to raise LIBTMUX_SAFETY if a change "
                        + "is genuinely needed.\n";
            case MUTATING ->
                "\nSAFETY\nThis server can read and change tmux but cannot destroy: killing a "
                        + "pane, window, session or server is not offered. Ask the operator to set "
                        + "LIBTMUX_SAFETY=destructive if something really has to be ended.\n";
            case DESTRUCTIVE ->
                "\nSAFETY\nEverything is offered, including tmux_kill, which ends processes "
                        + "and cannot be undone. It refuses to end the pane this conversation runs through "
                        + "unless confirm_self is set.\n";
        };
    }
}
