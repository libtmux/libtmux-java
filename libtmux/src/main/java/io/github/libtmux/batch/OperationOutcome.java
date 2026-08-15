package io.github.libtmux.batch;

/** What became of one operation in a batch. */
public enum OperationOutcome {
    /** tmux ran it and it succeeded. */
    COMPLETE,
    /** tmux ran it and it failed. Everything after it was discarded. */
    FAILED,
    /** tmux never reached it, because an earlier operation failed. */
    SKIPPED,
    /** Whether tmux applied it cannot be determined. */
    UNKNOWN
}
