package io.github.libtmux.control;

import io.github.libtmux.LibTmuxException;
import org.jspecify.annotations.Nullable;

/**
 * The control client ended, and this subscription will not resume.
 *
 * <p>Attach again with {@link io.github.libtmux.Server#control} and read a snapshot. The events
 * this subscription did not deliver are not replayed. {@link #standardError()} is what the tmux
 * process wrote to its error stream, bounded, and empty when it wrote nothing.
 */
public final class ControlEndedException extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    private final String standardError;
    private final boolean standardErrorTruncated;

    ControlEndedException(String standardError, boolean standardErrorTruncated, @Nullable Throwable cause) {
        super(message(standardError, standardErrorTruncated), cause);
        this.standardError = standardError;
        this.standardErrorTruncated = standardErrorTruncated;
    }

    /** The error text captured before the process ended, possibly truncated. */
    public String standardError() {
        return standardError;
    }

    /** Whether the error stream continued past the captured bound. */
    public boolean standardErrorTruncated() {
        return standardErrorTruncated;
    }

    private static String message(String standardError, boolean truncated) {
        if (standardError.isEmpty()) {
            return "control client ended";
        }
        return "control client ended: " + standardError + (truncated ? " (truncated)" : "");
    }
}
