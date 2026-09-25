package io.github.libtmux.transport;

import io.github.libtmux.exception.DispatchException;
import java.util.Optional;

/**
 * Runs one tmux command and blocks until it has an answer.
 *
 * <p>Implementations are thread-safe and {@link #close()} is idempotent. A call begun before close
 * either completes under the documented contract or fails with its dispatch certainty intact; a
 * call begun after close fails with {@link io.github.libtmux.exception.ServerClosedException}, an
 * {@link IllegalStateException}.
 */
public interface TmuxTransport extends AutoCloseable {

    /**
     * Runs the request to completion.
     *
     * <p>An implementation writes {@link CommandRequest#input()} to tmux's standard input and
     * closes it. A command that reads standard input and is given none reads end of file, which
     * tmux reports as success over an empty result rather than as a failure.
     *
     * @param request what to run, what it reads, and how long to wait
     * @return the exit status and both channels; a nonzero exit is a result, not a failure
     * @throws DispatchException if the command could not be run to completion, carrying how
     *     certain it is that tmux applied it
     * @throws IllegalStateException if this transport is closed
     */
    CommandResult execute(CommandRequest request);

    /**
     * Runs a request expected to remain blocked until another request through this transport
     * releases it.
     *
     * <p>The default shares ordinary admission. A transport with bounded concurrency may override
     * this to keep release and observation requests from queuing behind every waiter.
     */
    default CommandResult executeWaiting(CommandRequest request) {
        return execute(request);
    }

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

    /**
     * How this transport starts a process that stays attached.
     *
     * <p>Empty when the transport cannot. A command-only fake is in that set, and so is any realm
     * that has not said how its processes are born. Callers that need a control client then fail
     * instead of starting one on the local machine.
     */
    default Optional<ControlCarrier> controlCarrier() {
        return Optional.empty();
    }

    /**
     * Receives a report after each command this transport runs.
     *
     * <p>The default ignores it. A transport that cannot see its own commands leaves the observer
     * unset rather than inventing timings.
     */
    default void observe(OperationObserver observer) {}

    /**
     * How many requests this transport runs at once; the rest wait for a turn.
     *
     * @return the bound, or {@link Integer#MAX_VALUE} for a transport that sets none
     */
    default int admissionBound() {
        return Integer.MAX_VALUE;
    }

    /** Releases every resource and destroys every child still running. Idempotent. */
    @Override
    void close();
}
