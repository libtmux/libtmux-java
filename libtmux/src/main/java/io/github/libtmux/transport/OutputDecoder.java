package io.github.libtmux.transport;

import io.github.libtmux.internal.Utf8;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a drained pipe into lines the way callers already expect.
 *
 * <p>tmux emits pane content, so the bytes are not guaranteed to be UTF-8. A malformed byte is
 * escaped as {@code \xNN} rather than replaced with U+FFFD, which keeps the original byte
 * recoverable and matches what CPython's {@code backslashreplace} produces.
 *
 * <p>The two channels split differently: stdout keeps interior blank lines and drops only trailing
 * ones, stderr drops every blank wherever it appears. That asymmetry is not a tidy rule, it is the
 * observable shape callers depend on.
 */
final class OutputDecoder {

    private OutputDecoder() {}

    static List<String> stdoutLines(byte[] bytes) {
        List<String> lines = split(decode(bytes));
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isEmpty()) {
            end--;
        }
        return List.copyOf(lines.subList(0, end));
    }

    static List<String> stderrLines(byte[] bytes) {
        List<String> kept = new ArrayList<>();
        for (String line : split(decode(bytes))) {
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return List.copyOf(kept);
    }

    /** UTF-8 with {@code backslashreplace}, then universal newlines. */
    private static String decode(byte[] bytes) {
        String decoded = Utf8.backslashReplace(bytes);
        return decoded.indexOf('\r') < 0
                ? decoded
                : decoded.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<String> split(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = text.indexOf('\n', start)) >= 0) {
            lines.add(text.substring(start, index));
            start = index + 1;
        }
        lines.add(text.substring(start));
        return lines;
    }
}
