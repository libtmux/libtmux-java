package io.github.libtmux;

import java.util.List;

/**
 * No tmux daemon answered on this endpoint.
 *
 * <p>Distinct from a capture that failed for some other reason: nothing was there to answer, rather
 * than an answer that could not be trusted. A caller wanting to start a daemon on demand can catch
 * exactly this instead of matching on a message.
 */
public final class ServerNotRunningException extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    public ServerNotRunningException(String message) {
        super(message);
    }

    ServerNotRunningException(String message, String command, int exitCode, List<String> errorLines) {
        super(message, null, command, exitCode, errorLines);
    }
}
