package io.github.libtmux;

/**
 * Where a new window is inserted relative to the current one.
 *
 * <p>Absent rather than a third constant when the caller does not care: tmux picking the next free
 * index is a different thing from being asked for a particular side, and a {@code DEFAULT} member
 * would have no flag to produce.
 */
public enum WindowPlacement {
    AFTER("-a"),
    BEFORE("-b");

    private final String flag;

    WindowPlacement(String flag) {
        this.flag = flag;
    }

    /** The tmux flag that places a window this way. */
    String flag() {
        return flag;
    }
}
