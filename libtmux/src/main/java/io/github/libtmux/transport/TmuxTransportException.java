package io.github.libtmux.transport;

import io.github.libtmux.LibTmuxException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A transport failure, carrying how certain it is that tmux applied the command.
 *
 * <p>The certainty is the point. "tmux never started" and "tmux timed out halfway" call for
 * opposite recovery, and a caller that cannot tell them apart has to treat every failure as the
 * dangerous one.
 */
public final class TmuxTransportException extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    // Deliberately not transient: an outcome lost in serialization would read as NOT_DISPATCHED to a
    // null-checking caller, which is the one answer that invites retrying a command tmux already ran.
    private final DispatchOutcome outcome;

    public TmuxTransportException(String message, DispatchOutcome outcome, @Nullable Throwable cause) {
        super(message, cause);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /** How certain the transport is that tmux applied the request. */
    public DispatchOutcome outcome() {
        return outcome;
    }
}
