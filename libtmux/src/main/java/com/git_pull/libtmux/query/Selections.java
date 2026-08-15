package com.git_pull.libtmux.query;

import com.git_pull.libtmux.LibTmuxException;
import java.util.Iterator;
import java.util.Optional;

/**
 * Cardinality with the ambiguity removed.
 *
 * <p>"No session matched" and "several sessions matched" are different bugs in a caller's code, so
 * they get different exceptions. {@code findFirst} stays on {@link java.util.stream.Stream} where it
 * already belongs; only the strict shapes need helpers.
 */
public final class Selections {

    private Selections() {}

    /** The single match, or a failure naming which way the count was wrong. */
    public static <T> T exactlyOne(Iterable<T> matches) {
        Iterator<T> iterator = matches.iterator();
        if (!iterator.hasNext()) {
            throw new NoMatchException("expected exactly one match, found none");
        }
        T first = iterator.next();
        if (iterator.hasNext()) {
            throw new MultipleMatchesException("expected exactly one match, found more than one");
        }
        return first;
    }

    /**
     * The single match if there is one, empty if there is none — but still a failure for several.
     *
     * <p>Returning the first of many would make a caller's ambiguous query look answered.
     */
    public static <T> Optional<T> oneOrEmpty(Iterable<T> matches) {
        Iterator<T> iterator = matches.iterator();
        if (!iterator.hasNext()) {
            return Optional.empty();
        }
        T first = iterator.next();
        if (iterator.hasNext()) {
            throw new MultipleMatchesException("expected at most one match, found more than one");
        }
        return Optional.of(first);
    }

    /** Nothing matched where the caller required something. */
    public static final class NoMatchException extends LibTmuxException {
        private static final long serialVersionUID = 1L;

        NoMatchException(String message) {
            super(message);
        }
    }

    /** Several things matched where the caller required at most one. */
    public static final class MultipleMatchesException extends LibTmuxException {
        private static final long serialVersionUID = 1L;

        MultipleMatchesException(String message) {
            super(message);
        }
    }
}
