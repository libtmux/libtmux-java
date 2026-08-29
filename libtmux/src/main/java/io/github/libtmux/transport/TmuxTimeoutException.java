package io.github.libtmux.transport;

import org.jspecify.annotations.Nullable;

/** A request crossed the process boundary but did not finish before its deadline. */
public final class TmuxTimeoutException extends TmuxTransportException {

    private static final long serialVersionUID = 1L;

    public TmuxTimeoutException(String message, @Nullable Throwable cause) {
        this(message, DispatchOutcome.UNKNOWN, cause);
    }

    /** Creates a deadline failure with the request's dispatch certainty. */
    public TmuxTimeoutException(String message, DispatchOutcome outcome, @Nullable Throwable cause) {
        super(message, outcome, cause);
    }
}
