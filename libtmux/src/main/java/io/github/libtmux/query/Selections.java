package io.github.libtmux.query;

import io.github.libtmux.exception.CardinalityException;
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

    /**
     * The single match, or a failure naming which way the count was wrong.
     *
     * @throws CardinalityException.NoMatch if nothing matched
     * @throws CardinalityException.MultipleMatches if more than one did; the scan stops at the
     *     second match, so its count is two
     */
    public static <T> T exactlyOne(Iterable<T> matches) {
        Iterator<T> iterator = matches.iterator();
        if (!iterator.hasNext()) {
            throw new CardinalityException.NoMatch("expected exactly one match, found none");
        }
        T first = iterator.next();
        if (iterator.hasNext()) {
            throw multiple("exactly one", first, iterator.next());
        }
        return first;
    }

    /**
     * The single match if there is one, empty if there is none — but still a failure for several.
     *
     * <p>Returning the first of many would make a caller's ambiguous query look answered.
     *
     * @throws CardinalityException.MultipleMatches if more than one matched
     */
    public static <T> Optional<T> oneOrEmpty(Iterable<T> matches) {
        Iterator<T> iterator = matches.iterator();
        if (!iterator.hasNext()) {
            return Optional.empty();
        }
        T first = iterator.next();
        if (iterator.hasNext()) {
            throw multiple("at most one", first, iterator.next());
        }
        return Optional.of(first);
    }

    /** A single pass never reads past the second match, however large or lazy the source is. */
    private static CardinalityException.MultipleMatches multiple(String wanted, Object first, Object second) {
        return new CardinalityException.MultipleMatches(
                "expected " + wanted + " match, found more than one, starting with " + first + " and " + second, 2);
    }
}
