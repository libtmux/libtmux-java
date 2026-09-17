package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Waits for something to appear in a pane nobody here started.
 *
 * <p>The last resort among the waits, and the only one that is a heuristic. A daemon printing
 * {@code ready}, a dev server someone else launched, a build already running when the model
 * arrived: there is no command to append a signal to, so the screen is all there is to read.
 *
 * <p>Text already on screen when a cursorless call starts is never reported as a fresh match - a
 * pane that already says {@code ready} from an hour ago must not satisfy a wait for {@code ready}
 * the same way output that just arrived would, which is the failure that makes a scraping wait
 * untrustworthy. It is still reported, as its own outcome ({@code PRESENT_AT_ENTRY}) rather than
 * silently ignored: a caller with no earlier {@code cursor} to chain from has no other way to learn
 * that what it wanted was already there, and the alternative - timing out with empty output while
 * {@code capture_pane} shows the text in plain sight - is worse.
 *
 * <p>Patterns are plain text by default. A model asked to wait for {@code [FAILED]} means those
 * eight characters, and reading them as a regular expression would match a single letter instead —
 * so regular expressions are available, but only when asked for.
 */
final class WaitingForText {

    private WaitingForText() {}

    /**
     * @param outcome MATCHED, PRESENT_AT_ENTRY, STOPPED, TIMED_OUT or SERVER_GONE
     * @param matched the pattern that ended the wait, absent when none did
     * @param matchedLine the line it was found on
     * @param output the lines the wait saw - new ones it watched for, or, for PRESENT_AT_ENTRY, the
     *     ones already on screen when it started - newest last
     * @param cursor where to resume watching without re-reading these lines
     */
    record Waited(
            String paneId,
            String outcome,
            @Nullable String matched,
            @Nullable String matchedLine,
            List<String> output,
            boolean truncated,
            int linesDropped,
            String cursor,
            double seconds,
            double effectiveTimeout,
            @Nullable String note) {}

    static Waited waitFor(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        Duration timeout = Waits.requested(call);
        List<String> wantedSources = nonempty(call.strings("patterns"));
        List<String> stopSources = nonempty(call.strings("stop"));
        List<String> allSources = new ArrayList<>(wantedSources);
        allSources.addAll(stopSources);
        List<TextPatterns.Matcher> all = TextPatterns.compile(allSources, call.flag("regex", false));
        List<TextPatterns.Matcher> wanted = all.subList(0, wantedSources.size());
        List<TextPatterns.Matcher> stops = all.subList(wantedSources.size(), all.size());

        int budget = Trim.lineBudget(call);
        // A pane a caller just typed into can echo that text back before this wait even starts
        // watching; matching it there would report the caller's own input as the pane's answer
        // (D10). Excluded up front, from both matching and the lines a caller sees, using only what
        // this process itself just sent - nothing about a screen capture says "this line is an
        // echo" on its own.
        Optional<String> recentEcho = TypedEcho.recentFor(pane.id().value());
        long started = System.nanoTime();

        Optional<String> cursorArgument = call.maybe("cursor");
        Cursor cursor;
        if (cursorArgument.isPresent()) {
            cursor = Cursor.decode(cursorArgument.get());
        } else {
            // D1: with no cursor to resume from, "now" is not "empty" - text the pane already
            // printed is on screen whether it arrived a second ago or an hour ago, and a wait that
            // only looks forward from this instant would never see it, timing out with empty output
            // even while capture_pane shows the very thing it was asked for.
            Screen.Fresh entry = Screen.completeOnly(pane);
            cursor = entry.cursor();
            List<String> entryLines =
                    recentEcho.map(echo -> withoutEcho(entry.lines(), echo)).orElse(entry.lines());
            Waited early = presentAtEntry(pane, timeout, wanted, stops, budget, entryLines, cursor, started);
            if (early != null) {
                return early;
            }
        }
        Trim.Trimmed retained = new Trim.Trimmed(List.of(), 0);
        long deadline = started + timeout.toNanos();

        String outcome = "TIMED_OUT";
        TextPatterns.Matcher hit = null;
        String hitLine = null;

        while (true) {
            Screen.Fresh fresh = Screen.since(pane, cursor, budget);
            cursor = fresh.cursor();
            List<String> freshLines =
                    recentEcho.map(echo -> withoutEcho(fresh.lines(), echo)).orElse(fresh.lines());
            retained = Trim.append(retained, freshLines, budget);

            // Failure first: a build that has already printed "error:" is not going to print
            // "Listening on", and the wait that notices is the one that returns in seconds.
            Found stopped = find(stops, freshLines);
            if (stopped != null) {
                outcome = "STOPPED";
                hit = stopped.matcher();
                hitLine = stopped.line();
                break;
            }
            Found found = find(wanted, freshLines);
            if (found != null) {
                outcome = "MATCHED";
                hit = found.matcher();
                hitLine = found.line();
                break;
            }
            if (wanted.isEmpty() && !freshLines.isEmpty()) {
                outcome = "MATCHED";
                hitLine = freshLines.get(freshLines.size() - 1);
                break;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            long elapsed = System.nanoTime() - started;
            call.progress()
                    .report(
                            Duration.ofNanos(elapsed),
                            timeout,
                            "watching " + pane.id().value() + " for "
                                    + (wanted.isEmpty() ? "any output" : wanted.size() + " pattern(s)"));
            if (!sleep()) {
                break;
            }
        }

        if (!pane.server().isAlive()) {
            outcome = "SERVER_GONE";
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        return new Waited(
                pane.id().value(),
                outcome,
                hit == null ? null : hit.source(),
                hitLine,
                retained.lines(),
                retained.truncated(),
                retained.dropped(),
                cursor.encode(),
                Math.round(seconds * 100) / 100.0,
                Waits.asSeconds(timeout),
                note(outcome, wanted, stops));
    }

    private static @Nullable String note(
            String outcome, List<TextPatterns.Matcher> wanted, List<TextPatterns.Matcher> stops) {
        if ("TIMED_OUT".equals(outcome)) {
            return stops.isEmpty()
                    ? "Nothing matched before the deadline. Pass 'cursor' to carry on from here without "
                            + "re-reading, and pass 'stop' with the failure text so a run that fails is not "
                            + "waited on to the ceiling."
                    : "Nothing matched before the deadline. Pass 'cursor' to carry on from here without "
                            + "re-reading these lines.";
        }
        if ("STOPPED".equals(outcome)) {
            return "A stop pattern matched, so the wait ended early. This is a failure, not a success.";
        }
        if ("SERVER_GONE".equals(outcome)) {
            return "The tmux server ended while waiting; nothing this call watched can be relied on.";
        }
        return wanted.isEmpty() ? "Matched on any new output, because no patterns were given." : null;
    }

    /**
     * A wanted or stop pattern already on the pane's screen the moment this call started, distinct
     * from a fresh match found while watching (D1). Only reachable when the caller passed no {@code
     * cursor}: chaining a cursor from an earlier call means the caller already has this screen, and
     * "present at entry" would just repeat that earlier answer.
     *
     * <p>With no patterns at all there is nothing to be "already there": that mode matches any
     * <em>new</em> output, and entry content is by definition not new, so this never fires for it -
     * unlike a real match or stop, which name something specific to be present or absent.
     *
     * @return the answer, or null when nothing at entry matched and the ordinary wait must run
     */
    private static @Nullable Waited presentAtEntry(
            Pane pane,
            Duration timeout,
            List<TextPatterns.Matcher> wanted,
            List<TextPatterns.Matcher> stops,
            int budget,
            List<String> entryLines,
            Cursor cursor,
            long started) {
        Found stopped = find(stops, entryLines);
        Found found = find(wanted, entryLines);
        Found hitFound = stopped != null ? stopped : found;
        if (hitFound == null) {
            return null;
        }
        boolean fromStop = stopped != null;
        Trim.Trimmed retained = Trim.append(new Trim.Trimmed(List.of(), 0), entryLines, budget);
        TextPatterns.Matcher hit = hitFound.matcher();
        String hitLine = hitFound.line();
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        String note = fromStop
                ? "A stop pattern was already on screen before this call started watching - this is a "
                        + "failure, not a success. Pass the returned 'cursor' to watch only for what "
                        + "comes after it."
                : "Already on screen before this call started watching - not fresh output. Pass the "
                        + "returned 'cursor' to watch only for what comes after it.";
        return new Waited(
                pane.id().value(),
                "PRESENT_AT_ENTRY",
                hit.source(),
                hitLine,
                retained.lines(),
                retained.truncated(),
                retained.dropped(),
                cursor.encode(),
                Math.round(seconds * 100) / 100.0,
                Waits.asSeconds(timeout),
                note);
    }

    private record Found(TextPatterns.Matcher matcher, String line) {}

    private static @Nullable Found find(List<TextPatterns.Matcher> matchers, List<String> lines) {
        for (String line : lines) {
            for (TextPatterns.Matcher matcher : matchers) {
                if (matcher.matches(line)) {
                    return new Found(matcher, line);
                }
            }
        }
        return null;
    }

    /**
     * Drops a fresh line that carries a recently typed echo, so it is never scanned for a match and
     * never shown to a caller as though the pane had printed it.
     *
     * <p>A line long enough to wrap is split across rows by the terminal, not by anything tmux or
     * this code chose, so no single captured row need carry the whole echo even though it is
     * exactly what is on screen. The rows are searched joined into one string, with nothing between
     * them the way a wrapped line joins its own rows.
     *
     * <p>Only the echo's own characters are taken out, rather than every row it touches. A row
     * holds whatever the terminal put there, and the same row can carry the tail of what was typed
     * and the start of what the pane then printed — a prompt and the output beside it, once the
     * prompt has wrapped. Dropping such a row would lose real output and leave the wait timing out
     * against text that is plainly on screen. A row left empty by the removal held nothing else and
     * goes; a row with anything left keeps it.
     */
    static List<String> withoutEcho(List<String> lines, String echo) {
        if (echo.isEmpty() || lines.isEmpty()) {
            return lines;
        }
        int[] lineStart = new int[lines.size() + 1];
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            lineStart[index] = joined.length();
            joined.append(lines.get(index));
        }
        lineStart[lines.size()] = joined.length();

        String text = joined.toString();
        boolean[] echoed = new boolean[text.length()];
        int from = 0;
        int at;
        while ((at = text.indexOf(echo, from)) >= 0) {
            int end = at + echo.length();
            for (int position = at; position < end; position++) {
                echoed[position] = true;
            }
            from = end;
        }

        List<String> kept = new ArrayList<>(lines.size());
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            row.setLength(0);
            for (int position = lineStart[index]; position < lineStart[index + 1]; position++) {
                if (!echoed[position]) {
                    row.append(text.charAt(position));
                }
            }
            boolean emptiedByRemoval = row.isEmpty() && lineStart[index] < lineStart[index + 1];
            if (!emptiedByRemoval) {
                kept.add(row.toString());
            }
        }
        return kept;
    }

    private static boolean sleep() {
        try {
            Thread.sleep(Waits.POLL.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<String> nonempty(List<String> sources) {
        return sources.stream().filter(source -> !source.isEmpty()).toList();
    }
}
