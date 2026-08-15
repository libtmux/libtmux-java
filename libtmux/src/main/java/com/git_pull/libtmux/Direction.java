package com.git_pull.libtmux;

/**
 * Which way to move or grow something.
 *
 * <p>An enum rather than tmux's {@code -U -D -L -R} flags, so a call site says which way it means
 * and a wrong direction cannot be spelled at all.
 */
public enum Direction {
    UP("-U"),
    DOWN("-D"),
    LEFT("-L"),
    RIGHT("-R");

    private final String flag;

    Direction(String flag) {
        this.flag = flag;
    }

    /** The tmux flag that selects this direction. */
    String flag() {
        return flag;
    }
}
