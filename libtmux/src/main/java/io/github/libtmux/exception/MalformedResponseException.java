package io.github.libtmux.exception;

/**
 * tmux answered, and the answer did not have the shape this library asked for.
 *
 * <p>A listing row with the wrong number of fields, a number that did not parse, an identity that
 * was not one row: this library's own template disagreed with what tmux sent. Check the tmux release
 * is inside the supported range, and report it if it is.
 */
public final class MalformedResponseException extends LibTmuxException {
    private static final long serialVersionUID = 1L;

    public MalformedResponseException(String message) {
        super(message, null);
    }

    public MalformedResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
