package io.github.libtmux.exception;

/**
 * The server answered, and the session, window, pane, buffer or server incarnation a handle
 * addresses is gone.
 *
 * <p>Re-resolve the handle — run again the lookup or filter that produced it — or stop. An
 * unreachable server is a {@link ServerUnavailableException} instead, which calls for something else
 * entirely.
 */
public final class TargetGoneException extends LibTmuxException {
    private static final long serialVersionUID = 1L;

    public TargetGoneException(String message) {
        super(message, null);
    }
}
