package com.git_pull.libtmux;

/**
 * How big something is, in terminal cells.
 *
 * <p>A pair rather than two loose numbers, because width and height are only meaningful together and
 * a call site that takes them separately can transpose them without the compiler noticing.
 *
 * @param width columns
 * @param height rows
 */
public record Dimensions(int width, int height) {

    public Dimensions {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("dimensions are negative: " + width + "x" + height);
        }
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
