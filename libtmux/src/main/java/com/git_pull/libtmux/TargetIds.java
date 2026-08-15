package com.git_pull.libtmux;

import java.util.Objects;

/** Shared validation for the sigil-prefixed ids tmux hands out. */
final class TargetIds {

    private TargetIds() {}

    /**
     * Rejects anything tmux would not read as an id of this kind.
     *
     * <p>A bare {@code 0} is the case worth catching: tmux would accept it as a target and resolve
     * it as a name or an index, silently addressing something else.
     */
    static void require(String value, char sigil, String kind) {
        Objects.requireNonNull(value, "value");
        if (value.length() < 2 || value.charAt(0) != sigil) {
            throw new IllegalArgumentException(
                    "not a " + kind + " id, expected " + sigil + " followed by digits: " + value);
        }
    }
}
