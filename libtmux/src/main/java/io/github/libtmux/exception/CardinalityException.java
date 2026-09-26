package io.github.libtmux.exception;

/**
 * A lookup that required exactly one match, or at most one, did not get it.
 *
 * <p>Loosen or tighten the filter, or handle the case explicitly.
 */
public abstract sealed class CardinalityException extends LibTmuxException
        permits CardinalityException.NoMatch, CardinalityException.MultipleMatches {

    private static final long serialVersionUID = 1L;

    CardinalityException(String message) {
        super(message, null);
    }

    /** Nothing matched where the caller required something. */
    public static final class NoMatch extends CardinalityException {
        private static final long serialVersionUID = 1L;

        public NoMatch(String message) {
            super(message);
        }
    }

    /** More than one thing matched where the caller required at most one. */
    public static final class MultipleMatches extends CardinalityException {
        private static final long serialVersionUID = 1L;

        private final int atLeast;

        /** @param atLeast how many matched, at least; never less than two */
        public MultipleMatches(String message, int atLeast) {
            super(message);
            if (atLeast < 2) {
                throw new IllegalArgumentException("multiple matches are at least two, not " + atLeast);
            }
            this.atLeast = atLeast;
        }

        /**
         * How many matched, at least.
         *
         * <p>Exact when the matches came from a capture this library holds, as a handle lookup's do.
         * {@link io.github.libtmux.query.Selections} stops at the second match, since its source may be
         * lazy or unbounded, so there it is two.
         */
        public int atLeast() {
            return atLeast;
        }
    }
}
