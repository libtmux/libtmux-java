package io.github.libtmux.transport;

/** How certain the transport is that tmux applied a request. */
public enum DispatchOutcome {
    /** The process never started, so tmux cannot have applied anything. */
    NOT_DISPATCHED,
    /** The process ran to completion and both channels were drained. */
    COMPLETE,
    /** tmux may already have applied the command; the result is not knowable. */
    UNKNOWN;

    /**
     * Whether the exact same request may be sent again after this outcome.
     *
     * <p>A request that never reached tmux may always be resent. One that may have reached it may be
     * resent only when it changes nothing. One that completed is not a dispatch question at all:
     * whether to act on tmux's answer is the caller's decision, so this answers {@code false}.
     *
     * @param idempotence whether every command in the request only reads
     */
    public boolean canRetryVerbatim(Idempotence idempotence) {
        return switch (this) {
            case NOT_DISPATCHED -> true;
            case COMPLETE -> false;
            case UNKNOWN -> idempotence == Idempotence.IDEMPOTENT;
        };
    }
}
