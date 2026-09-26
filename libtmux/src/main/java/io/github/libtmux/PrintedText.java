package io.github.libtmux;

import io.github.libtmux.format.Tokens;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes tmux's printed output against the version the server actually reports, framed with
 * process-local markers so a reply is read back exactly whatever quirks that version's own printing
 * has.
 */
final class PrintedText {

    private PrintedText() {}

    private static final String VERSION_MARK = Tokens.perProcess() + "-version";

    /**
     * Printed after what a command printed. tmux ends its output with a line break and the transport
     * drops trailing blank lines, so text that itself ends in line breaks would lose them; with this
     * after it, nothing trailing is blank.
     */
    static final String END_MARK = Tokens.perProcess() + "-end";

    /** A format for {@code display-message -p} that {@link #printed(List)} reads back exactly. */
    static String expansion(String format) {
        return versioned(format) + END_MARK;
    }

    /**
     * This format with the server's version expanded ahead of it, so the reply can be decoded
     * without another tmux process. See {@link TmuxFormats#printed(String, TmuxVersion)}.
     */
    static String versioned(String format) {
        return "#{version}" + VERSION_MARK + format;
    }

    /** The expansion of a {@link #versioned} format, as tmux held the text. */
    static String printed(List<String> reported) {
        String all = String.join("\n", reported);
        int mark = all.indexOf(VERSION_MARK);
        String text = mark < 0 ? all : all.substring(mark + VERSION_MARK.length());
        if (mark >= 0) {
            try {
                text = TmuxFormats.printed(text, TmuxVersion.parse(all.substring(0, mark)));
            } catch (RuntimeException notAVersion) {
                // Not tmux's own answer, as from a test double; keep what came back.
            }
        }
        // After the decoding, not before: 3.4 escapes a $ that a letter follows, and the mark may
        // begin with one, so a value ending in $ is only whole once that escape is undone.
        return text.endsWith(END_MARK) ? text.substring(0, text.length() - END_MARK.length()) : text;
    }

    /** As {@link #printed(List)}, for a listing whose every row began with {@link #versioned}. */
    static List<String> printedRows(List<String> rows) {
        List<String> text = new ArrayList<>(rows.size());
        for (String row : rows) {
            text.add(printed(List.of(row)));
        }
        return text;
    }

    /** The three commands {@link Server#printed(io.github.libtmux.snapshot.ServerSnapshot, List)} sends as one group. */
    static List<List<String>> framed(List<String> argv) {
        return List.of(
                List.of("display-message", "-p", "#{version}"), argv, List.of("display-message", "-p", END_MARK));
    }

    /** Strips the version line and end mark {@link #framed} added, decoding what is left. */
    static CommandResult unwrap(CommandResult result) {
        if (result.stdout().isEmpty()) {
            return result;
        }
        List<String> output = result.stdout().subList(1, result.stdout().size());
        if (!output.isEmpty() && output.getLast().equals(END_MARK)) {
            output = output.subList(0, output.size() - 1);
        }
        try {
            output = TmuxFormats.printed(
                    output, TmuxVersion.parse(result.stdout().get(0)));
        } catch (RuntimeException notAVersion) {
            // Not tmux's own answer, as from a test double; keep what came back.
        }
        return new CommandResult(result.exitCode(), List.copyOf(output), result.stderr());
    }
}
