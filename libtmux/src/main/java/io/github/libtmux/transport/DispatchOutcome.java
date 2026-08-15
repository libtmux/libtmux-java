package io.github.libtmux.transport;

/** How certain the transport is that tmux applied a request. */
public enum DispatchOutcome {
    /** The process never started, so tmux cannot have applied anything. */
    NOT_DISPATCHED,
    /** The process ran to completion and both channels were drained. */
    COMPLETE,
    /** tmux may already have applied the command; the result is not knowable. */
    UNKNOWN
}
