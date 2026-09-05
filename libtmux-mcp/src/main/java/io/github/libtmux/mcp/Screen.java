package io.github.libtmux.mcp;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane;
import io.github.libtmux.batch.BatchResult;
import io.github.libtmux.batch.OperationResult;
import java.util.ArrayList;
import java.util.List;
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
final class Screen {

    /**
     * How far back a look reaches beyond what the caller asked for.
     *
     * <p>The cursor's line has to be inside the window for continuity to be checked at all. A screen
     * of slack past the caller's own budget covers the pane it is watching plus the output that
     * arrived while the last answer was in flight; anything further back is rare enough to be worth
     * a second look rather than a bigger one every time.
     */
    private static final int SLACK_LINES = 256;

    private static final int CURSOR_RECOVERY_LINES = 20_000;

    private Screen() {}

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
                Cursor.of(look.serverPid(), pane.id().value(), look.complete()),
                true);
    }

    /** What arrived since {@code from}, and where to resume. */
    static Fresh since(Pane pane, @Nullable Cursor from, int budget) {
        if (from == null) {
            return from(pane);
        }
        String paneId = pane.id().value();
        if (!paneId.equals(from.paneId())) {
            throw new IllegalArgumentException(
                    "that cursor belongs to pane " + from.paneId() + ", not " + paneId + "; each pane has its own");
        }
        Look look = look(pane, budget + SLACK_LINES);
        Fresh answer = resolve(from, look);
        if (answer != null) {
            return answer;
        }
        Look recovery = look(pane, CURSOR_RECOVERY_LINES);
        Fresh resumed = resolve(from, recovery);
        if (resumed != null) {
            return resumed;
        }
        List<String> written = recovery.complete();
        return new Fresh(List.copyOf(written), Cursor.of(recovery.serverPid(), paneId, written), false);
    }

    static int cursorRecoveryLines() {
        return CURSOR_RECOVERY_LINES;
    }

    /**
     * @return the answer, or null when the cursor's line is older than this look reached
     */
    private static @Nullable Fresh resolve(Cursor from, Look look) {
        if (from.serverPid() != look.serverPid()) {
            throw new IllegalArgumentException(
                    "that cursor belongs to an earlier tmux server; omit 'cursor' to start again");
        }
        List<String> written = look.complete();
        Cursor now = Cursor.of(look.serverPid(), from.paneId(), written);
        if (from.anchors().isEmpty()) {
            if (!look.reachedStartOfHistory()) {
                return null;
            }
            return new Fresh(List.copyOf(written), now, true);
        }
        int after = uniqueAnchorEnd(written, from.anchors());
        if (after < 0 && !look.reachedStartOfHistory()) {
            return null;
        }
        if (after < 0) {
            return new Fresh(List.copyOf(written), now, false);
        }
        List<String> fresh = after < written.size() ? List.copyOf(written.subList(after, written.size())) : List.of();
        return new Fresh(fresh, now, true);
    }

    /** Returns the end of one unambiguous context match, or -1. */
    private static int uniqueAnchorEnd(List<String> lines, List<String> anchors) {
        int match = -1;
        for (int start = 0; start + anchors.size() <= lines.size(); start++) {
            boolean same = true;
            for (int offset = 0; offset < anchors.size(); offset++) {
                if (!Cursor.digest(lines.get(start + offset)).equals(anchors.get(offset))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                if (match >= 0) {
                    return -1;
                }
                match = start + anchors.size();
            }
        }
        return match;
    }

    /**
     * One capture and the pane's own position, from one tmux invocation.
     *
     * @param finished how many of the captured lines the terminal's cursor has moved past
     * @param reachedStartOfHistory whether the capture began at the oldest line tmux still holds
     */
    private record Look(List<String> lines, int finished, boolean reachedStartOfHistory, long serverPid) {

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
        BatchResult read = pane.batch()
                .add("capture-pane", "-p", "-t", id, "-S", start)
                .add("display-message", "-p", "-t", id, "#{pid} #{history_size} #{cursor_y}")
                .run();
        if (read.operations().size() != 2 || read.operations().stream().anyMatch(operation -> !operation.succeeded())) {
            throw new LibTmuxException("could not read pane content and position as one batch");
        }
        OperationResult capture = read.operations().get(0);
        OperationResult position = read.operations().get(1);
        long[] numbers = numbers(position.stdout());
        long serverPid = numbers[0];
        int history = Math.toIntExact(numbers[1]);
        int cursorY = Math.toIntExact(numbers[2]);
        int first = everything || lookback > history ? 0 : lookback <= 0 ? history : history - lookback;
        // The cursor's row is the first unfinished line, and it sits that far below the history.
        return new Look(capture.stdout(), history + cursorY - first, first == 0, serverPid);
    }

    private static long[] numbers(List<String> stdout) {
        if (stdout.size() != 1) {
            throw new LibTmuxException("tmux returned no unambiguous pane position");
        }
        String[] words = stdout.get(0).trim().split("\\s+", -1);
        if (words.length != 3) {
            throw new LibTmuxException("tmux returned a malformed pane position");
        }
        long[] read = new long[3];
        for (int index = 0; index < read.length; index++) {
            try {
                read[index] = Long.parseLong(words[index]);
            } catch (NumberFormatException e) {
                throw new LibTmuxException("tmux returned a nonnumeric pane position", e);
            }
        }
        if (read[0] <= 0 || read[1] < 0 || read[2] < 0) {
            throw new LibTmuxException("tmux returned an invalid pane position");
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
