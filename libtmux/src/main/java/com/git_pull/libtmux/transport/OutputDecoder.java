package com.git_pull.libtmux.transport;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        // UTF-8 never decodes to more chars than it has bytes, so this buffer cannot overflow and
        // an escape can never straddle it.
        CharBuffer out = CharBuffer.allocate(bytes.length);
        StringBuilder text = new StringBuilder(bytes.length);
        // Decode runs at least once even for empty input: flush() rejects a decoder still in RESET.
        while (true) {
            CoderResult result = decoder.decode(in, out, true);
            drainInto(out, text);
            if (result.isUnderflow()) {
                break;
            }
            for (int offset = 0; offset < result.length(); offset++) {
                escape(text, in.get(in.position() + offset));
            }
            in.position(in.position() + result.length());
        }
        decoder.flush(out);
        drainInto(out, text);
        String decoded = text.toString();
        return decoded.indexOf('\r') < 0
                ? decoded
                : decoded.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void escape(StringBuilder text, byte value) {
        text.append("\\x")
                .append(Character.forDigit((value >> 4) & 0xf, 16))
                .append(Character.forDigit(value & 0xf, 16));
    }

    private static void drainInto(CharBuffer out, StringBuilder text) {
        out.flip();
        text.append(out);
        out.clear();
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
