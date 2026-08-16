package io.github.libtmux.mcp;

import java.util.List;
import java.util.StringJoiner;

/**
 * Composes text that a pane's own shell will run.
 *
 * <p>Everything this server types into a pane is read by an interactive shell it does not control,
 * so every value it interpolates — a socket path, a tmux binary, a channel name — has to survive
 * that shell's word splitting and expansion as exactly one word.
 *
 * <p>Single quotes are the only POSIX construct that preserves everything, including {@code $},
 * backticks, and newlines. A single quote inside is closed, escaped and reopened.
 */
final class Shell {

    private Shell() {}

    /** One argument, quoted so a shell reads it as the literal text given. */
    static String quote(String argument) {
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    /** A command line whose every word is quoted. */
    static String quoteAll(List<String> argv) {
        StringJoiner line = new StringJoiner(" ");
        argv.forEach(word -> line.add(quote(word)));
        return line.toString();
    }
}
