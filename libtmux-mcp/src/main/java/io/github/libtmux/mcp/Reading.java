package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * What panes are showing.
 *
 * <p>Every read here is bounded and says so when it drops anything. An unbounded terminal read is
 * the cheapest way to spend a model's whole context on one call: a pane with a large history and a
 * build log in it is megabytes, and none of it was asked for.
 */
final class Reading {

    private Reading() {}

    record Captured(
            String paneId,
            int lines,
            List<String> content,
            boolean truncated,
            int linesDropped,
            String cursor,
            @Nullable String note) {}

    record Since(
            String paneId,
            int newLines,
            List<String> content,
            String cursor,
            boolean continuous,
            boolean truncated,
            int linesDropped,
            @Nullable String note) {}

    record Hit(String paneId, String session, String window, String line) {}

    record Found(
            int count,
            int panesSearched,
            List<Hit> matches,
            @Nullable String note) {}

    /** What a pane shows now, newest last, with a cursor for watching it from here. */
    static Captured capture(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        boolean history = call.flag("history", false);
        List<String> lines = Watching.withoutTrailingBlanks(
                history ? pane.capture(spec -> spec.fromStartOfHistory()) : pane.capture());
        Trim.Trimmed trimmed = Trim.tail(lines, Trim.lineBudget(call));
        return new Captured(
                pane.id().value(),
                trimmed.lines().size(),
                trimmed.lines(),
                trimmed.truncated(),
                trimmed.dropped(),
                Cursor.at(pane, Watching.Geometry.of(pane).history(), lines).encode(),
                trimmed.truncated()
                        ? "The oldest " + trimmed.dropped() + " lines were dropped to fit 'max_lines'. "
                                + "What is here is the most recent output."
                        : null);
    }

    /**
     * Only what arrived since a cursor.
     *
     * <p>This is how a pane is watched without paying for it repeatedly. The tenth look at a build
     * log costs the few lines it added, not the nine screens already read.
     */
    static Since since(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        Cursor from = call.maybe("cursor").map(Cursor::decode).orElse(null);
        if (from != null && !from.paneId().equals(pane.id().value())) {
            throw new IllegalArgumentException("that cursor belongs to pane " + from.paneId() + ", not "
                    + pane.id().value() + "; each pane has its own");
        }
        Watching.Fresh fresh = Watching.since(pane, from);
        Trim.Trimmed trimmed = Trim.tail(fresh.lines(), Trim.lineBudget(call));
        return new Since(
                pane.id().value(),
                trimmed.lines().size(),
                trimmed.lines(),
                fresh.cursor().encode(),
                fresh.continuous(),
                trimmed.truncated(),
                trimmed.dropped(),
                note(fresh, trimmed, from));
    }

    private static @Nullable String note(Watching.Fresh fresh, Trim.Trimmed trimmed, @Nullable Cursor from) {
        if (!fresh.continuous()) {
            return "The lines already delivered are no longer where the cursor left them: the pane was "
                    + "cleared, or its output has outrun the history tmux keeps. What is here is what the "
                    + "pane shows now, and it does not follow on from the last call.";
        }
        if (trimmed.truncated()) {
            return "More arrived than 'max_lines' allows; the oldest " + trimmed.dropped()
                    + " of it was dropped. Raise 'max_lines' or call again sooner.";
        }
        if (from != null && fresh.lines().isEmpty()) {
            return "Nothing new since the last call.";
        }
        return null;
    }

    /**
     * Finds text across panes, so "which pane is the server in" is one call rather than one per pane.
     *
     * <p>Searches what each pane is showing, not its whole history: the history of forty panes is
     * both far more than a model can read and far more tmux than one call should cost.
     */
    static Found search(Call call) {
        Server server = call.server();
        String pattern = call.string("pattern");
        boolean regex = call.flag("regex", false);
        Pattern compiled = compile(pattern, regex);
        int perPane = Math.clamp(call.integer("max_matches_per_pane", 5), 1, 50);

        List<Pane> panes = server.panes();
        List<Hit> hits = new ArrayList<>();
        for (Pane pane : panes) {
            int kept = 0;
            for (String line : Watching.withoutTrailingBlanks(pane.capture())) {
                if (kept >= perPane) {
                    break;
                }
                boolean matched = compiled == null
                        ? line.contains(pattern)
                        : compiled.matcher(line).find();
                if (matched) {
                    hits.add(new Hit(
                            pane.id().value(),
                            pane.window().session().name(),
                            pane.window().name(),
                            line.strip()));
                    kept++;
                }
            }
        }
        Trim.Trimmed budget = Trim.tail(hits.stream().map(Hit::line).toList(), Trim.lineBudget(call));
        List<Hit> shown = hits.size() > budget.lines().size()
                ? List.copyOf(hits.subList(hits.size() - budget.lines().size(), hits.size()))
                : List.copyOf(hits);
        return new Found(
                shown.size(),
                panes.size(),
                shown,
                shown.isEmpty()
                        ? "No pane is currently showing that. This searches what panes show now, not their "
                                + "history — text that has scrolled away will not be found."
                        : null);
    }

    private static @Nullable Pattern compile(String pattern, boolean regex) {
        if (!regex) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("'" + pattern + "' is not a valid regular expression: "
                    + e.getDescription() + ". Omit 'regex' to search for it as plain text instead");
        }
    }
}
