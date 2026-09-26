package io.github.libtmux.exception;

import io.github.libtmux.transport.DispatchOutcome;
import java.util.Objects;

/**
 * A handle was used after the {@link io.github.libtmux.Server} it belongs to was closed.
 *
 * <p>Programmer error, so deliberately outside {@link LibTmuxException}'s sealed tree: an exhaustive
 * match over operational failures should not have to decide about a lifecycle bug. {@link
 * #outcome()} says whether tmux may have run the call that raced the close.
 */
public final class ServerClosedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    // Not transient, for the same reason as DispatchException's: a lost outcome reads as NOT_DISPATCHED.
    private final DispatchOutcome outcome;

    public ServerClosedException(String message, DispatchOutcome outcome) {
        super(message);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * {@link DispatchOutcome#NOT_DISPATCHED} when the server was closed before the call reached tmux,
     * {@link DispatchOutcome#UNKNOWN} when a concurrent close ended a tmux already running it.
     */
    public DispatchOutcome outcome() {
        return outcome;
    }
}
