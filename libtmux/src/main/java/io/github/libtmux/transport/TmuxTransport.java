package io.github.libtmux.transport;

/**
 * Runs one tmux command and blocks until it has an answer.
 *
 * <p>Implementations are thread-safe and {@link #close()} is idempotent. A call begun before close
 * either completes under the documented contract or fails with its dispatch certainty intact; a
 * call begun after close fails with {@link IllegalStateException}.
 */
public interface TmuxTransport extends AutoCloseable {

    /**
     * Runs the request to completion.
     *
     * @param request what to run and how long to wait
     * @return the exit status and both channels; a nonzero exit is a result, not a failure
     * @throws TmuxTransportException if the command could not be run to completion, carrying how
     *     certain it is that tmux applied it
     * @throws IllegalStateException if this transport is closed
     */
    CommandResult execute(CommandRequest request);

    /**
     * Names the execution realm this transport reaches tmux through.
     *
     * <p>Entity identity is scoped by it. Two unrelated realms can both hold a server at the same
     * socket path, and entities from them must not compare equal, so a transport that reaches a
     * different machine, container or user should return something stable and distinct.
     *
     * @return a stable realm name; the default is the local process's own view of the filesystem
     */
    default String realm() {
        return "local";
    }

    /** Releases every resource and destroys every child still running. Idempotent. */
    @Override
    void close();
}
