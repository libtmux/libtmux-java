package io.github.libtmux.jackson;

import io.github.libtmux.LibTmuxException;

/**
 * A document could not be read as the expression it claims to be.
 *
 * <p>Every failure here is a refusal rather than a best effort. An expression read wrongly does not
 * announce itself: it silently matches the wrong things, which is worse than not loading at all.
 */
public final class SchemaException extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    public SchemaException(String message) {
        super(message);
    }
}
