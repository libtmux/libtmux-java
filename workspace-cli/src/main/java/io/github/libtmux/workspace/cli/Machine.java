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
}
