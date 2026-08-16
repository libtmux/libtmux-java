package io.github.libtmux.mcp;

import java.util.Locale;

/**
 * How much damage a tool can do, and how much a server is willing to offer.
 *
 * <p>The same scale answers both questions, so a launcher configured at {@link #READONLY} serves
 * exactly the tools whose safety is {@code READONLY}. A tool above the configured ceiling is not
 * listed at all rather than listed and refused: a model cannot be tempted by a tool it never saw,
 * and an error it can do nothing about is wasted context.
 *
 * <p>The names are the ones every port of libtmux uses, so an operator who has configured one has
 * configured all of them.
 */
public enum Safety {

    /** Reads state. Running it twice tells you the same thing and changes nothing. */
    READONLY(0),

    /** Changes state a user could undo: sends keys, creates windows, sets options. */
    MUTATING(1),

    /** Destroys something that does not come back: kills a pane, a session, or the server. */
    DESTRUCTIVE(2);

    /**
     * How much this level permits, stated rather than taken from the declaration order. Reordering
     * the constants must not quietly widen what a server offers.
     */
    private final int rank;

    Safety(int rank) {
        this.rank = rank;
    }

    /** Whether a server holding this ceiling will serve a tool of {@code required} safety. */
    public boolean allows(Safety required) {
        return required.rank <= rank;
    }

    /** The name an operator writes, which is the lowercase one every port accepts. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the name an operator wrote.
     *
     * @throws IllegalArgumentException naming what was accepted, since the caller is a person
     */
    public static Safety ofWireName(String name) {
        for (Safety safety : values()) {
            if (safety.wireName().equals(name)) {
                return safety;
            }
        }
        throw new IllegalArgumentException(
                "unknown safety level '" + name + "'; expected readonly, mutating or destructive");
    }
}
