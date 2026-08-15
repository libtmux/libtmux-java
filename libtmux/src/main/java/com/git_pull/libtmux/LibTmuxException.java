package com.git_pull.libtmux;

import org.jspecify.annotations.Nullable;

/**
 * The root of every operational failure this library raises.
 *
 * <p>Unchecked, because a tmux failure can arise from any call and forcing every caller to declare
 * it would say nothing. This covers transport, tmux, hydration and query failures. It does not cover
 * programmer error: a null argument is a {@link NullPointerException}, an invalid value an
 * {@link IllegalArgumentException}, and use after close an {@link IllegalStateException}.
 */
public class LibTmuxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LibTmuxException(String message) {
        super(message);
    }

    public LibTmuxException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
