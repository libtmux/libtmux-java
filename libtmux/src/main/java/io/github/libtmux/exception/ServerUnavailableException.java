package io.github.libtmux.exception;

import java.util.List;

/**
 * No tmux daemon answered on this endpoint.
 *
 * <p>Nothing was there to answer, rather than an answer that could not be trusted. Start a server on
 * demand, or treat the endpoint as down.
 */
public final class ServerUnavailableException extends LibTmuxException {
    private static final long serialVersionUID = 1L;

    public ServerUnavailableException(String message) {
        super(message, null);
    }

    /** A daemon found absent by the command that tried to reach it. */
    public ServerUnavailableException(String message, String command, int exitCode, List<String> errorLines) {
        super(message, null, command, exitCode, errorLines);
    }
}
