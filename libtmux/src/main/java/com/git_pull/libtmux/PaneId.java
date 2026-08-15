package com.git_pull.libtmux;

/**
 * A tmux pane id, such as {@code %2}.
 *
 * <p>Stable for the life of the pane, unlike a pane index, which shifts as neighbours come and go.
 *
 * @param value the id including its {@code %} sigil
 */
public record PaneId(String value) {

    public PaneId {
        TargetIds.require(value, '%', "pane");
    }

    @Override
    public String toString() {
        return value;
    }
}
