package io.github.libtmux;

/**
 * Where a pane's top-left corner sits in its window, in terminal cells.
 *
 * <p>A pair rather than two loose numbers, for the reason {@link Dimensions} gives: read together,
 * and a call site that took them separately could transpose them without the compiler noticing.
 *
 * @param left columns from the window's left edge
 * @param top rows from the window's top edge
 */
public record PanePosition(int left, int top) {

    public PanePosition {
        if (left < 0 || top < 0) {
            throw new IllegalArgumentException("position is negative: " + left + "," + top);
        }
    }

    @Override
    public String toString() {
        return left + "," + top;
    }
}
