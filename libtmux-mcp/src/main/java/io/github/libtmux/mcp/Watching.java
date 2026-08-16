package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.batch.BatchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reads the part of a pane a caller has not seen.
 *
 * <p>What is new and whether anything was lost are answered together, from one look. The look holds
 * the line the cursor last delivered: if that line is still the one it was, everything after it is
 * new; if it is not, the pane was cleared or its history rolled past, and saying so is better than
 * handing back lines that do not follow the ones before them.
 *
 * <p>The capture and the pane's own position <strong>must come from one tmux invocation</strong>.
 * Where a line sits in a capture depends on how far the pane has scrolled, so two invocations can
 * disagree about it — and then a pane that merely scrolled between them looks exactly like a pane
 * that was cleared. Measured on a pane under continuous output, two separate reads disagreed in 40
 * of 60 attempts; batched into one invocation, none of 60 did, because tmux does not process pane
 * output between two commands of the same invocation.
 */
final class Watching {

    /**
     * How far back a look reaches beyond what the caller asked for.
     *
     * <p>The cursor's line has to be inside the window for continuity to be checked at all. A screen
     * of slack past the caller's own budget covers the pane it is watching plus the output that
     * arrived while the last answer was in flight; anything further back is rare enough to be worth
     * a second look rather than a bigger one every time.
     */
    private static final int SLACK_LINES = 256;

    private Watching() {}

    /**
     * @param lines what the caller has not seen
     * @param cursor where to resume next time
     * @param continuous whether these lines follow the ones already delivered
     */
    record Fresh(List<String> lines, Cursor cursor, boolean continuous) {}

    /** Everything the pane shows, when a caller has no cursor yet. */
    static Fresh from(Pane pane) {
        return everything(pane, false);
    }

    /**
     * Everything the pane shows, or everything it still holds, with a cursor for watching on from
     * there.
     *
     * <p>Batched for the same reason a resumed read is: a cursor built from a capture and a position
     * read separately describes a place that never existed.
     */
    static Fresh everything(Pane pane, boolean withHistory) {
        Look look = look(pane, withHistory ? Integer.MAX_VALUE : 0);
        // Everything on screen is shown, including a line still being drawn and whatever a
        // full-screen program has put below the cursor. The cursor to resume from is the last
        // finished line, so watching on from here cannot trip over a half-written one.
        return new Fresh(
                withoutTrailingBlanks(look.lines()),
                Cursor.of(pane.id().value(), look.firstAbsolute(), look.complete()),
                true);
    }

    /** What arrived since {@code from}, and where to resume. */
    static Fresh since(Pane pane, @Nullable Cursor from, int budget) {
        if (from == null) {
            return from(pane);
        }
        Look look = look(pane, budget + SLACK_LINES);
        Fresh answer = resolve(from, look);
        if (answer != null) {
            return answer;
        }
        // The cursor's line is older than the look reached. Looking as far back as tmux keeps
        // anything settles it either way: found, and the pane merely ran ahead; absent, and its
        // history really has rolled past what was delivered. A look that started at the oldest line
        // there is always answers, which is what makes this terminate.
        return Objects.requireNonNull(
                resolve(from, look(pane, Integer.MAX_VALUE)), "a look at the whole history always answers");
    }

    /**
     * @return the answer, or null when the cursor's line is older than this look reached
     */
    private static @Nullable Fresh resolve(Cursor from, Look look) {
        // Where the last delivered line sits in what was captured. Both numbers come from the same
        // invocation, so this cannot be off by however far the pane scrolled meanwhile.
        int anchor = from.absolute() - 1 - look.firstAbsolute();
        if (anchor < 0 && !look.reachedStartOfHistory()) {
            return null;
        }
        List<String> written = look.complete();
        // Wherever this ends up, every finished line is now delivered — so the cursor says the same
        // thing on every path, and only what is handed back differs.
        Cursor now = Cursor.of(from.paneId(), look.firstAbsolute(), written);

        // A cursor at the very beginning has no line before it to check against, so there is nothing
        // it could fail to follow on from.
        boolean continuous = from.absolute() == 0
                || (anchor >= 0
                        && anchor < written.size()
                        && Cursor.digest(written.get(anchor)).equals(from.anchor()));
        if (!continuous) {
            return new Fresh(List.copyOf(written), now, false);
        }
        int after = from.absolute() - look.firstAbsolute();
        List<String> fresh =
                after < written.size() ? List.copyOf(written.subList(Math.max(after, 0), written.size())) : List.of();
        return new Fresh(fresh, now, true);
    }

    /**
     * One capture and the pane's own position, from one tmux invocation.
     *
     * @param firstAbsolute how many lines the pane had written above the first line captured
     * @param finished how many of the captured lines the terminal's cursor has moved past
     * @param reachedStartOfHistory whether the capture began at the oldest line tmux still holds
     */
    private record Look(List<String> lines, int firstAbsolute, int finished, boolean reachedStartOfHistory) {

        /**
         * The lines that are finished being written.
         *
         * <p>A terminal is a grid, not a log: the line the cursor sits on is still being drawn, and a
         * capture can catch {@code line-123} as {@code line-12}. Anchoring a cursor to a line like
         * that reports a discontinuity on the next read, when nothing was lost at all — so only lines
         * the cursor has left behind are ever delivered or anchored to.
         */
        List<String> complete() {
            return withoutTrailingBlanks(lines.subList(0, Math.clamp(finished, 0, lines.size())));
        }
    }

    private static Look look(Pane pane, int lookback) {
        String id = pane.id().value();
        // Everything tmux keeps, when asked for more than it could have.
        boolean everything = lookback >= Integer.MAX_VALUE;
        String start = everything ? "-" : lookback <= 0 ? "0" : String.valueOf(-lookback);
        BatchResult read = pane.server()
                .batch()
                .add("capture-pane", "-p", "-t", id, "-S", start)
                .add("display-message", "-p", "-t", id, "#{history_size} #{cursor_y}")
                .run();
        List<String> lines = read.operations().get(0).stdout();
        int[] position = numbers(read.operations().get(1).stdout());
        int history = position[0];
        int first = everything || lookback > history ? 0 : lookback <= 0 ? history : history - lookback;
        // The cursor's row is the first unfinished line, and it sits that far below the history.
        return new Look(lines, first, history + position[1] - first, first == 0);
    }

    private static int[] numbers(List<String> stdout) {
        String[] words = stdout.isEmpty() ? new String[0] : stdout.get(0).trim().split("\\s+");
        int[] read = new int[2];
        for (int index = 0; index < read.length; index++) {
            try {
                read[index] = index < words.length ? Integer.parseInt(words[index]) : 0;
            } catch (NumberFormatException e) {
                read[index] = 0;
            }
        }
        return read;
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
        return end == lines.size() ? List.copyOf(lines) : new ArrayList<>(lines.subList(0, end));
    }
}
