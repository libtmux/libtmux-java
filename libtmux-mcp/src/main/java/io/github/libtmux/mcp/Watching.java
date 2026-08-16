package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads the part of a pane a caller has not seen.
 *
 * <p>One capture answers both questions it has to answer: what is new, and whether anything was
 * lost. The capture starts one line before the cursor, so the line it already delivered comes back
 * first — if that line is still the one it was, everything after it is new; if it is not, the pane
 * was cleared or its history rolled past, and saying so is better than handing back lines that do
 * not follow the ones before them.
 */
final class Watching {

    private Watching() {}

    /**
     * @param lines what the caller has not seen
     * @param cursor where to resume next time
     * @param continuous whether these lines follow the ones already delivered
     */
    record Fresh(List<String> lines, Cursor cursor, boolean continuous) {}

    /**
     * How far a pane has scrolled and how tall it is, read together.
     *
     * <p>One expansion rather than two: both numbers are needed on every call, and asking twice
     * costs a second tmux command for an answer that has to agree with the first anyway.
     */
    record Geometry(int history, int rows) {

        static Geometry of(Pane pane) {
            String[] parts =
                    pane.expand("#{history_size} #{pane_height}").trim().split("\\s+");
            return new Geometry(number(parts, 0), Math.max(1, number(parts, 1)));
        }

        private static int number(String[] parts, int index) {
            try {
                return index < parts.length ? Integer.parseInt(parts[index]) : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** Everything the pane shows, when a caller has no cursor yet. */
    static Fresh from(Pane pane) {
        Geometry geometry = Geometry.of(pane);
        List<String> visible = withoutTrailingBlanks(pane.capture());
        return new Fresh(visible, Cursor.at(pane, geometry.history(), visible), true);
    }

    /** What arrived since {@code from}, and where to resume. */
    static Fresh since(Pane pane, @Nullable Cursor from) {
        if (from == null) {
            return from(pane);
        }
        Geometry geometry = Geometry.of(pane);
        // One line before the first unseen one: reading it back is what proves continuity. Clamped
        // into the range tmux can address, so a cursor from a pane that has since been cleared asks
        // for a line that exists and is told it is the wrong one, rather than failing the call.
        int probe =
                Math.clamp((long) from.absolute() - geometry.history() - 1, -geometry.history(), geometry.rows() - 1);
        List<String> got = withoutTrailingBlanks(pane.capture(spec -> spec.from(probe)));
        if (got.isEmpty()) {
            // Nothing is written at or after the probe: the pane was cleared back past the cursor.
            return new Fresh(List.of(), from, false);
        }
        boolean continuous = Cursor.digest(got.get(0)).equals(from.anchor());
        List<String> fresh = continuous ? List.copyOf(got.subList(1, got.size())) : List.copyOf(got);
        Cursor now = new Cursor(
                from.paneId(), geometry.history() + probe + got.size(), Cursor.digest(got.get(got.size() - 1)));
        return new Fresh(fresh, now, continuous);
    }

    /**
     * A pane is as tall as its window whether or not anything has been written that far down, so the
     * rows below the last line of output are blank and would otherwise be delivered as content — and
     * would then be counted as already seen, hiding the output that later overwrites them.
     */
    static List<String> withoutTrailingBlanks(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return end == lines.size() ? lines : new ArrayList<>(lines.subList(0, end));
    }
}
