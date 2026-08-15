package io.github.libtmux.format;

import io.github.libtmux.LibTmuxException;

/** A listing row did not have the shape its template asked for. */
public final class TmuxFormatException extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    public TmuxFormatException(String message) {
        super(message);
    }
}
