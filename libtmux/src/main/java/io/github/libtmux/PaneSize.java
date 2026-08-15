package io.github.libtmux;

/**
 * How big a new pane should be.
 *
 * <p>One value with two spellings, not a size and a percentage. tmux takes both through {@code -l},
 * so there is no pair of fields to hold consistent and no "both were given" error to raise — the
 * Python sibling's {@code size}/{@code percentage} pair and the {@code ValueError} guarding it both
 * disappear.
 *
 * <p>tmux's own {@code -p} flag is never emitted. It is broken in 3.4, which reads the {@code -l}
 * argument while handling {@code -p} and fails with {@code size missing}; {@code -l 25%} produces the
 * same pane on every supported release.
 */
public sealed interface PaneSize {

    /** A size in terminal cells. */
    record Cells(int count) implements PaneSize {
        public Cells {
            if (count < 1) {
                throw new IllegalArgumentException("cells is not positive: " + count);
            }
        }
    }

    /** A share of what is being split. */
    record Percent(int share) implements PaneSize {
        public Percent {
            if (share < 1 || share > 100) {
                throw new IllegalArgumentException("percent outside 1..100: " + share);
            }
        }
    }

    /** A size in terminal cells. */
    static PaneSize cells(int count) {
        return new Cells(count);
    }

    /** A share of what is being split, between 1 and 100. */
    static PaneSize percent(int share) {
        return new Percent(share);
    }

    /** What follows {@code -l}. */
    default String flagValue() {
        return switch (this) {
            case Cells cells -> Integer.toString(cells.count());
            case Percent percent -> percent.share() + "%";
        };
    }
}
