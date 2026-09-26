package io.github.libtmux.exception;

import java.util.List;

/**
 * tmux ran a command to completion and refused it.
 *
 * <p>The request reached a live server and tmux answered no: a duplicate session name, a target it
 * cannot find, an option it does not know. Resending the same command gets the same answer, so read
 * {@link #errorLines()} and change the request.
 */
public final class CommandRejectedException extends LibTmuxException {
    private static final long serialVersionUID = 1L;

    public CommandRejectedException(String message) {
        super(message, null);
    }

    /** A refusal of {@code command}, with tmux's exit status and the lines it printed. */
    public CommandRejectedException(String message, String command, int exitCode, List<String> errorLines) {
        super(message, null, command, exitCode, errorLines);
    }
}
