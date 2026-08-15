package io.github.libtmux;

/**
 * Which sides of its window a pane touches.
 *
 * <p>Grouped rather than four loose flags, because they are read together to answer one question —
 * where in the window this pane sits — and four booleans in a row is an invitation to transpose two.
 *
 * @param top whether the pane touches the top of its window
 * @param bottom whether it touches the bottom
 * @param left whether it touches the left
 * @param right whether it touches the right
 */
public record PaneEdges(boolean top, boolean bottom, boolean left, boolean right) {

    /** Whether the pane fills its window, touching every side. */
    public boolean fillsWindow() {
        return top && bottom && left && right;
    }
}
