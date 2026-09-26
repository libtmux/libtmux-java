package io.github.libtmux;

import io.github.libtmux.catalog.Advanced;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.StringJoiner;
import kotlin.annotations.jvm.ReadOnly;

/**
 * The line typed at a pane's shell to run one command to its end, and how its output is read back.
 *
 * <p>Completion is not inferred from the screen. The command runs in a subshell whose trap prints an
 * end marker carrying {@code $?} and then signals a private {@code wait-for} channel through the
 * same tmux binary and socket, so the wait is tmux's own and the status is the shell's. The trap
 * covers interrupt and terminate as well as exit, so a command someone stops with {@code C-c} ends
 * the run and reports the status it was stopped with — commonly 130 — rather than leaving a wait
 * with nothing to end it.
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
@Advanced
public final class PaneCommand {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Exit, interrupt and terminate: the ways the wrapper ends that it can still report from. */
    private static final String TRAPPED = "0 2 15";

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
        // Interrupt and terminate are trapped beside exit so a command someone stops still reports
        // what it ended as, rather than leaving a run that never ends. The handler prints before it
        // clears the traps, because reading {@code $?} has to come before anything overwrites it,
        // and clearing them stops the exit trap running the body a second time.
        String finish = end + "; \\trap - " + TRAPPED + "; " + signal + "; \\exit 0";
        return " ( \\trap " + quote(finish) + " " + TRAPPED + "; " + start + "; ( \\eval " + quote(command) + " ) )";
    }

    /**
     * What lies between the two markers, and the status the end marker carried.
     *
     * <p>The start marker is matched as a whole row after trimming, the end marker as the end of
     * one — never by containment, because the echo of the typed line holds both as substrings and
     * must not be mistaken for either. The asymmetry is the shell's: nothing can precede the start
     * marker on its row, since it prints before the command runs, while the command's own last line
     * precedes the end marker whenever it carried no closing newline. That line is output, and is
     * returned as such.
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
        String last = "";
        for (int index = start + 1; index < lines.size(); index++) {
            // Trailing only: leading space is the command's indentation, while the trailing kind can
            // be padding tmux added when it rejoined the wrapped rows.
            String line = lines.get(index).stripTrailing();
            OptionalInt read = status(line);
            if (read.isPresent()) {
                end = index;
                status = read;
                last = ahead(line);
                break;
            }
        }
        if (start < 0) {
            // The frame is gone: output outgrew the history, or the command cleared the screen.
            // Everything that is not plumbing is better than nothing, and is said to be inexact.
            return new Framed(
                    kept(
                            lines.stream()
                                    .filter(line -> !line.contains(startMark) && !line.contains(endMark))
                                    .toList(),
                            last),
                    false,
                    status);
        }
        return new Framed(kept(lines.subList(start + 1, end < 0 ? lines.size() : end), last), end >= 0, status);
    }

    /**
     * What the command printed on the marker's own row: its last line, when that line carried no
     * newline to push the marker onto the next row. A terminal's interrupt echo sits between the two
     * and is the terminal's, not the command's, so a run of those is taken off the end.
     */
    private String ahead(String line) {
        String text = line.substring(0, line.lastIndexOf(endMark + ":"));
        while (text.length() >= 2 && text.charAt(text.length() - 2) == '^') {
            text = text.substring(0, text.length() - 2);
        }
        return text.stripTrailing();
    }

    /**
     * The framed rows, with anything the marker shared its row with kept as the last of them.
     *
     * <p>Each row loses its trailing space, because tmux's does not mean anything. Asking it to
     * rejoin wrapped rows also asks it to keep trailing spaces, and it pads the rows out to do so on
     * 3.2a though not on 3.7c — measured — so one command answered {@code built} on one release and
     * {@code built} followed by fifteen spaces on another. {@code -T} would ask tmux for less but
     * arrived in 3.4, below the range this supports. A terminal cannot tell a printed trailing space
     * from a cell nothing ever wrote, so dropping both is the only answer that is the same
     * everywhere.
     */
    private static List<String> kept(List<String> lines, String last) {
        List<String> all = new ArrayList<>(lines.size() + 1);
        lines.forEach(line -> all.add(line.stripTrailing()));
        if (!last.isEmpty()) {
            all.add(last);
        }
        return List.copyOf(all);
    }

    /** The exit status a row carries at its end, or empty when the row does not end in one. */
    public OptionalInt status(String line) {
        String prefix = endMark + ":";
        // The marker ends its row rather than starting it. A command whose last line carried no
        // newline leaves that line ahead of the marker, and a terminal writes its interrupt echo in
        // the same place; requiring the marker to start the row read both as no status at all. The
        // echo of the typed line cannot end this way: it writes "$?" after the colon, not digits.
        int at = line.lastIndexOf(prefix);
        if (at < 0) {
            return OptionalInt.empty();
        }
        String digits = line.substring(at + prefix.length());
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
    public record Framed(@ReadOnly List<String> lines, boolean exact, OptionalInt status) {

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
