package io.github.libtmux;

import java.util.List;

/**
 * Where a new pane goes relative to the one being split.
 *
 * <p>Separate from {@link Direction}, which moves and grows existing panes with {@code -U -D -L -R}.
 * Splitting speaks a different dialect: an axis, and whether the new pane comes first.
 */
public enum SplitDirection {
    /** Below the target, which is what tmux does when asked for nothing. */
    BELOW("-v", false),
    ABOVE("-v", true),
    RIGHT("-h", false),
    LEFT("-h", true);

    private final String axis;
    private final boolean first;

    SplitDirection(String axis, boolean first) {
        this.axis = axis;
        this.first = first;
    }

    /** The tmux flags that place a pane this way. */
    List<String> flags() {
        return first ? List.of(axis, "-b") : List.of(axis);
    }
}
