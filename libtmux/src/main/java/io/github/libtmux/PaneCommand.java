package io.github.libtmux;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The line typed at a pane's shell to run one command to its end, and how its output is read back.
 *
 * <p>Completion is not inferred from the screen. The command runs in a subshell whose exit trap
 * prints an end marker carrying {@code $?} and then signals a private {@code wait-for} channel
 * through the same tmux binary and socket, so the wait is tmux's own and the status is the shell's.
 *
 * <p>The output is cut out by framing: a start marker is printed before the command and the end
 * marker after it, and only lines strictly between them are returned. The shell echoes the whole
 * typed line, which contains both markers — but only inside quotes, never as a line of its own that
 * equals a marker, so matching complete marker lines tells plumbing from output even when the echo
 * wraps.
 *
 * <p>{@link Pane#run} is built on this, and it is public for a caller that needs the same framing
 * around orchestration of its own — the MCP server holds a pane for other clients while a run is
 * uncertain, which the library has no reason to.
 */
public final class PaneCommand {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The shells whose syntax the typed line is written in. */
    private static final Set<String> POSIX_SHELLS = Set.of("sh", "ash", "bash", "dash", "ksh", "mksh", "pdksh", "zsh");

    private final String startMark;
    private final String endMark;
    private final String channel;

    private PaneCommand(String nonce) {
        this.startMark = nonce + "-s";
        this.endMark = nonce + "-e";
        this.channel = "ch_" + nonce;
    }

    /** A run with markers and a channel no other run shares. */
    public static PaneCommand fresh() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return new PaneCommand("lt" + HexFormat.of().formatHex(nonce));
    }

    /** The {@code wait-for} channel the typed line signals once the command has ended. */
    public String channel() {
        return channel;
    }

    /**
     * The line to type.
     *
     * <p>Typed at the pane's own interactive shell rather than a fresh one, so the command sees the
     * environment a person set up there — a virtualenv, a loaded module, a directory someone changed
     * into. The leading space asks bash ({@code HISTCONTROL=ignorespace}) and zsh ({@code
     * HIST_IGNORE_SPACE}) to keep the plumbing out of the history; a shell set to neither records it.
     * Builtins are called as {@code \\trap}, {@code \\eval} and {@code \\exit} so an alias of the same
     * name cannot take them over.
     *
     * <p>The line begins with that space; strip it to have the shell record the line like any other.
     *
     * @param tmux the client the pane's shell calls: an absolute binary and its {@code -S} socket
     */
    public String typed(List<String> tmux, String command) {
        String start = quoteAll(append(tmux, "display-message", "-p", startMark));
        String end = quoteAll(append(tmux, "display-message", "-p")) + " " + quote(endMark + ":") + "\"$?\"";
        String signal = quoteAll(append(tmux, "wait-for", "-S", channel));
        String finish = end + "; " + signal + "; \\exit 0";
        return " ( \\trap " + quote(finish) + " 0; " + start + "; ( \\eval " + quote(command) + " ) )";
    }

    /**
     * What lies strictly between the two marker lines, and the status the end marker carried.
     *
     * <p>Markers are matched as whole lines after trimming, never by containment: the echo of the
     * typed line holds both as substrings and must not be mistaken for either.
     */
    public Framed frame(List<String> lines) {
        int start = -1;
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (lines.get(index).trim().equals(startMark)) {
                start = index;
                break;
            }
        }
        OptionalInt status = OptionalInt.empty();
        int end = -1;
        for (int index = start + 1; index < lines.size(); index++) {
            OptionalInt read = status(lines.get(index).trim());
            if (read.isPresent()) {
                end = index;
                status = read;
                break;
            }
        }
        if (start < 0) {
            // The frame is gone: output outgrew the history, or the command cleared the screen.
            // Everything that is not plumbing is better than nothing, and is said to be inexact.
            return new Framed(
                    lines.stream()
                            .filter(line -> !line.contains(startMark) && !line.contains(endMark))
                            .toList(),
                    false,
                    status);
        }
        return new Framed(List.copyOf(lines.subList(start + 1, end < 0 ? lines.size() : end)), end >= 0, status);
    }

    /** The exit status an end-marker line carries, or empty when the line is not one. */
    public OptionalInt status(String line) {
        String prefix = endMark + ":";
        if (!line.startsWith(prefix)) {
            return OptionalInt.empty();
        }
        String digits = line.substring(prefix.length());
        if (digits.isEmpty() || digits.length() > 3 || digits.chars().anyMatch(c -> c < '0' || c > '9')) {
            return OptionalInt.empty();
        }
        int value = Integer.parseInt(digits);
        return value <= 255 && Integer.toString(value).equals(digits) ? OptionalInt.of(value) : OptionalInt.empty();
    }

    /**
     * A run's output as read back from the screen.
     *
     * @param lines the command's output
     * @param exact whether both markers were found, so the lines are only the command's output
     * @param status the exit status, when the end marker was read
     */
    public record Framed(List<String> lines, boolean exact, OptionalInt status) {

        public Framed {
            lines = List.copyOf(lines);
        }
    }

    /** Refuses a pane whose shell would not read the typed line as written. */
    static void requirePosixShell(String current) {
        int slash = current.lastIndexOf('/');
        String name = slash < 0 ? current : current.substring(slash + 1);
        if (name.startsWith("-")) {
            name = name.substring(1);
        }
        if (!POSIX_SHELLS.contains(name)) {
            throw new IllegalStateException("running a command to its end needs a POSIX shell in the pane, and it is"
                    + " running '" + name + "'; send keys to it instead if typing into that program is intended");
        }
    }

    /** One word, quoted so a POSIX shell reads it as exactly the text given. */
    static String quote(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }

    private static String quoteAll(List<String> argv) {
        StringJoiner line = new StringJoiner(" ");
        argv.forEach(word -> line.add(quote(word)));
        return line.toString();
    }

    private static List<String> append(List<String> base, String... more) {
        List<String> argv = new ArrayList<>(base);
        argv.addAll(List.of(more));
        return argv;
    }
}
