package io.github.libtmux.mcp;

import java.util.List;

/**
 * Keeps an answer inside a budget a model can afford to read.
 *
 * <p>What survives is the tail. A pane's oldest lines are the ones a caller has most likely seen
 * already, and the reason to look at a terminal is almost always what it just did — a build that
 * ended, a prompt that came back, an error that stopped it.
 *
 * <p>Truncation is always reported. An answer silently shortened reads as a complete one, which is
 * how a model concludes a build printed nothing.
 */
final class Trim {

    /**
     * How many lines a capture returns when the caller does not say.
     *
     * <p>Roughly two screens: enough to hold a command, its output and the prompt that followed,
     * and small enough that reading one pane does not cost a turn's worth of context.
     */
    static final int DEFAULT_LINES = 200;

    /** The most a caller may ask for in one call, whatever it asks for. */
    static final int MAX_LINES = 5_000;

    /**
     * The most characters one answer may carry.
     *
     * <p>A line has no length limit: a pane showing minified JavaScript or a base64 blob is one
     * line of half a megabyte, and a line budget alone lets it through.
     */
    static final int MAX_CHARACTERS = 200_000;

    private Trim() {}

    /** The result of shortening, so a caller can say what it dropped rather than hide it. */
    record Trimmed(List<String> lines, int dropped) {

        boolean truncated() {
            return dropped > 0;
        }
    }

    /** Keeps the last {@code limit} lines, and within those the last {@link #MAX_CHARACTERS}. */
    static Trimmed tail(List<String> lines, int limit) {
        int allowed = Math.clamp(limit, 1, MAX_LINES);
        int from = Math.max(0, lines.size() - allowed);
        List<String> kept = lines.subList(from, lines.size());
        int dropped = from;

        long characters = 0;
        int start = kept.size();
        while (start > 0) {
            // The newline a caller will put back when it joins these, so the budget covers the text
            // as it will actually be read rather than as it is stored.
            long cost = kept.get(start - 1).length() + 1L;
            if (characters + cost > MAX_CHARACTERS) {
                break;
            }
            characters += cost;
            start--;
        }
        if (start > 0) {
            dropped += start;
            kept = kept.subList(start, kept.size());
        }
        return new Trimmed(List.copyOf(kept), dropped);
    }

    /** The line budget a caller asked for, or the default when it asked for nothing. */
    static int lineBudget(Call call) {
        return Math.clamp(call.integer("max_lines", DEFAULT_LINES), 1, MAX_LINES);
    }
}
