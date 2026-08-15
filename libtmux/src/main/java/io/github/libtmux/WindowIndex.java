package io.github.libtmux;

/**
 * Where a window sits in one session.
 *
 * <p>A position, not an identity: tmux renumbering moves a window to a different index, and the
 * same window linked into two sessions can hold a different index in each.
 *
 * @param value the index, as tmux reports it
 */
public record WindowIndex(int value) {

    public WindowIndex {
        if (value < 0) {
            throw new IllegalArgumentException("window index is negative: " + value);
        }
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
