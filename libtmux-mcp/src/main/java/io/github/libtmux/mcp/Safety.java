package io.github.libtmux.mcp;

import java.util.Locale;

/**
 * Which classes of tool a server is willing to offer.
 *
 * <p>This is an availability ceiling, not an effect annotation. {@link #MUTATING} includes commands
 * and pane input whose effects may be destructive; their MCP annotations say so independently. A
 * tool above the configured ceiling is not listed at all rather than listed and refused.
 *
 * <p>The names are the ones every port of libtmux uses, so an operator who has configured one has
 * configured all of them.
 */
public enum Safety {

    /** Offers only tools that read state. */
    READONLY(0),

    /** Also offers tools that change state, run commands, or send input. */
    MUTATING(1),

    /** Also offers dedicated tools that end a pane, session, or server. */
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
