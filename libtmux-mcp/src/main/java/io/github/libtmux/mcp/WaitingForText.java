package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Waits for something to appear in a pane nobody here started.
 *
 * <p>The last resort among the waits, and the only one that is a heuristic. A daemon printing
 * {@code ready}, a dev server someone else launched, a build already running when the model
 * arrived: there is no command to append a signal to, so the screen is all there is to read.
 *
 * <p>Only text that arrives <em>after</em> the call starts counts. A pane that already says
 * {@code ready} from an hour ago would otherwise satisfy every wait immediately, which is the
 * failure that makes a scraping wait untrustworthy.
 *
 * <p>Patterns are plain text by default. A model asked to wait for {@code [FAILED]} means those
 * eight characters, and reading them as a regular expression would match a single letter instead —
 * so regular expressions are available, but only when asked for.
 */
final class WaitingForText {

    private WaitingForText() {}

    /**
     * @param outcome MATCHED, STOPPED, TIMED_OUT or SERVER_GONE
     * @param matched the pattern that ended the wait, absent when none did
     * @param matchedLine the line it was found on
     * @param output the new lines the wait saw, newest last
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
        List<Matcher> wanted = matchers(call.strings("patterns"), call.flag("regex", false));
        List<Matcher> stops = matchers(call.strings("stop"), call.flag("regex", false));

        int budget = Trim.lineBudget(call);
        Cursor cursor = call.maybe("cursor")
                .map(Cursor::decode)
                .orElseGet(() -> Watching.from(pane).cursor());
        List<String> seen = new ArrayList<>();
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();

        String outcome = "TIMED_OUT";
        Matcher hit = null;
        String hitLine = null;

        while (true) {
            Watching.Fresh fresh = Watching.since(pane, cursor, budget);
            cursor = fresh.cursor();
            seen.addAll(fresh.lines());

            // Failure first: a build that has already printed "error:" is not going to print
            // "Listening on", and the wait that notices is the one that returns in seconds.
            Found stopped = find(stops, fresh.lines());
            if (stopped != null) {
                outcome = "STOPPED";
                hit = stopped.matcher();
                hitLine = stopped.line();
                break;
            }
            Found found = find(wanted, fresh.lines());
            if (found != null) {
                outcome = "MATCHED";
                hit = found.matcher();
                hitLine = found.line();
                break;
            }
            if (wanted.isEmpty() && !fresh.lines().isEmpty()) {
                outcome = "MATCHED";
                hitLine = fresh.lines().get(fresh.lines().size() - 1);
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
        Trim.Trimmed trimmed = Trim.tail(seen, Trim.lineBudget(call));
        return new Waited(
                pane.id().value(),
                outcome,
                hit == null ? null : hit.source(),
                hitLine,
                trimmed.lines(),
                trimmed.truncated(),
                trimmed.dropped(),
                cursor.encode(),
                Math.round(seconds * 100) / 100.0,
                Waits.asSeconds(timeout),
                note(outcome, wanted, stops));
    }

    private static @Nullable String note(String outcome, List<Matcher> wanted, List<Matcher> stops) {
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

    private record Found(Matcher matcher, String line) {}

    private static @Nullable Found find(List<Matcher> matchers, List<String> lines) {
        for (String line : lines) {
            for (Matcher matcher : matchers) {
                if (matcher.matches(line)) {
                    return new Found(matcher, line);
                }
            }
        }
        return null;
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

    private static List<Matcher> matchers(List<String> sources, boolean regex) {
        return sources.stream()
                .filter(source -> !source.isEmpty())
                .map(source -> Matcher.of(source, regex))
                .toList();
    }

    /** One thing to look for, and the text a caller asked for so a result can name it back. */
    private record Matcher(String source, @Nullable Pattern compiled) {

        static Matcher of(String source, boolean regex) {
            if (!regex) {
                return new Matcher(source, null);
            }
            try {
                return new Matcher(source, Pattern.compile(source));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("'" + source + "' is not a valid regular expression: "
                        + e.getDescription() + ". Omit 'regex' to match it as plain text instead");
            }
        }

        boolean matches(String line) {
            return compiled == null
                    ? line.contains(source)
                    : compiled.matcher(line).find();
        }
    }
}
