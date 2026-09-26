package io.github.libtmux.exception;

import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.Idempotence;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The request did not complete, with how certain it is that tmux applied it.
 *
 * <p>"tmux never started" and "tmux timed out halfway" call for opposite recovery, so the certainty
 * travels with the failure. {@link #safeToRetry()} answers the question a caller usually has: whether
 * sending the exact same request again can do harm.
 */
public abstract sealed class DispatchException extends LibTmuxException
        permits DispatchException.Failed, DispatchException.TimedOut {

    private static final long serialVersionUID = 1L;

    // Deliberately not transient: an outcome lost in serialization would read as NOT_DISPATCHED to a
    // null-checking caller, which is the one answer that invites retrying a command tmux already ran.
    private final DispatchOutcome outcome;
    private final Idempotence idempotence;

    DispatchException(String message, DispatchOutcome outcome, Idempotence idempotence, @Nullable Throwable cause) {
        super(message, cause);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.idempotence = Objects.requireNonNull(idempotence, "idempotence");
    }

    /** How certain the transport is that tmux applied the request. */
    public DispatchOutcome outcome() {
        return outcome;
    }

    /** Whether every command in the request only reads, so running it twice changes nothing. */
    public Idempotence idempotence() {
        return idempotence;
    }

    /**
     * Whether sending the exact same request again cannot apply it twice.
     *
     * @return {@link DispatchOutcome#canRetryVerbatim} for this failure's outcome and idempotence
     */
    public boolean safeToRetry() {
        return outcome.canRetryVerbatim(idempotence);
    }

    /** The transport failed for a reason other than a deadline: tmux could not start, or the channel broke. */
    public static final class Failed extends DispatchException {
        private static final long serialVersionUID = 1L;

        public Failed(String message, DispatchOutcome outcome, @Nullable Throwable cause) {
            this(message, outcome, Idempotence.NOT_IDEMPOTENT, cause);
        }

        public Failed(String message, DispatchOutcome outcome, Idempotence idempotence, @Nullable Throwable cause) {
            super(message, outcome, idempotence, cause);
        }
    }

    /** The deadline passed before the request finished. */
    public static final class TimedOut extends DispatchException {
        private static final long serialVersionUID = 1L;

        public TimedOut(String message, DispatchOutcome outcome, @Nullable Throwable cause) {
            this(message, outcome, Idempotence.NOT_IDEMPOTENT, cause);
        }

        public TimedOut(String message, DispatchOutcome outcome, Idempotence idempotence, @Nullable Throwable cause) {
            super(message, outcome, idempotence, cause);
        }
    }
}
