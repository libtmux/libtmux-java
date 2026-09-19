package io.github.libtmux.workspace.cli;

/**
 * The contract every {@code --json} and {@code --ndjson} record is written against.
 *
 * <p>Machine output is what this module is consumed through, so its vocabulary is declared once
 * rather than spelled as a literal at each site: a reader's parser and this file agree or the
 * compiler says so. The prose form is the README's machine-output section.
 */
final class Machine {
    private Machine() {}

    /** Carried by every record; bumped when a shape changes in a way a reader must notice. */
    static final int SCHEMA_VERSION = 1;

    /**
     * The {@code code} a machine-readable failure can carry.
     *
     * <p>The set is shared with the other ports of this tool: the same condition answers with the
     * same name whichever one a script calls. {@link #INTERRUPTED} is the exception, and is not a
     * verdict on the request — it says the process was signalled and stopped where it stood.
     */
    enum Code {
        /** The named workspace does not exist where discovery looked. */
        WORKSPACE_NOT_FOUND("workspace_not_found"),
        /** The document parsed as YAML but does not describe a workspace this can build. */
        INVALID_WORKSPACE("invalid_workspace"),
        /** The document uses a key native loading does not implement. */
        UNSUPPORTED_KEY("unsupported_key"),
        /** The session the command names is not on the server. */
        SESSION_NOT_FOUND("session_not_found"),
        /** The named session is on the server and is not what the document describes. */
        SESSION_MISMATCH("session_mismatch"),
        /** tmux itself could not be found or run. */
        TMUX_UNAVAILABLE("tmux_unavailable"),
        /** tmux ran and refused, or the server changed under the command. */
        TMUX_FAILED("tmux_failed"),
        /** A child program the document asked for could not be run, or ended badly. */
        SCRIPT_FAILED("script_failed"),
        /** The destination a capture was told to write already exists. */
        DESTINATION_EXISTS("destination_exists"),
        /** The command was invoked in a way that cannot be carried out. */
        USAGE("usage"),
        /** The process was signalled; what it reports is where it stopped, not a verdict. */
        INTERRUPTED("interrupted");

        private final String wire;

        Code(String wire) {
            this.wire = wire;
        }

        /** The name a consumer reads. */
        String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    /**
     * The {@code event} an {@code --ndjson} stream can carry.
     *
     * <p>Three places read these names — the log level a record is written at, the progress display,
     * and a consumer's own parser — and they were three independent switches over string literals,
     * where a new name silently fell through all of them. The level travels with the name here so
     * that cannot happen again.
     */
    enum Event {
        /** The invocation began. Carries nothing; written to the log only. */
        COMMAND_STARTED("command-started", "debug"),
        /** The invocation ended badly. The failure's own record says what happened. */
        COMMAND_FAILED("command-failed", "error"),
        /** A command's work began, with the count of inputs it will process. */
        STARTED("started", "info"),
        /** A command's work finished; carries that command's whole result. */
        COMPLETED("completed", "info"),
        /** A command's work stopped; carries the result envelope up to the failure. */
        FAILED("failed", "error"),
        /** Something the request asked for that will not happen, with its own code. */
        WARNING("warning", "warning"),
        /** One input of a load began. */
        WORKSPACE_STARTED("workspace-started", "debug"),
        /** One input of a load finished, carrying that input's effects. */
        WORKSPACE_COMPLETED("workspace-completed", "info"),
        /** A session this load owns now exists. */
        SESSION_CREATED("session-created", "debug"),
        /** A window exists, with the panes it will hold still to come. */
        WINDOW_CREATED("window-created", "debug"),
        /** A window and every pane in it are done. */
        WINDOW_COMPLETED("window-completed", "debug"),
        /** A pane exists; its commands have not been sent. */
        PANE_CREATED("pane-created", "debug"),
        /** A pane has had its commands sent. */
        PANE_COMPLETED("pane-completed", "debug"),
        /** A child program is about to run. */
        SCRIPT_STARTED("script-started", "debug"),
        /** A fragment of a child's output, as it arrived, naming the stream it came from. */
        SCRIPT_OUTPUT("script-output", "info"),
        /** A child program ended, with its status. */
        SCRIPT_COMPLETED("script-completed", "debug");

        private final String wire;
        private final String level;

        Event(String wire, String level) {
            this.wire = wire;
            this.level = level;
        }

        /** The name a consumer reads. */
        String wire() {
            return wire;
        }

        /** The level a record of this event is logged at. */
        String level() {
            return level;
        }

        @Override
        public String toString() {
            return wire;
        }
    }
}
