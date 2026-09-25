package io.github.libtmux.internal;

import io.github.libtmux.exception.UnencodableTextException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** The UTF-8 boundary shared by process and control transports, in both directions. */
public final class Utf8 {

    /**
     * What this JVM encodes a child process's arguments with.
     *
     * <p>{@code sun.jnu.encoding} is the one the process builder actually uses; {@code
     * native.encoding} is the documented name for the same choice and stands in when the internal
     * property is absent. Read once, because the locale that decides it is read before {@code main}
     * and cannot change afterwards.
     */
    private static final boolean ARGUMENTS_ARE_UTF8 =
            argumentsAreUtf8(System.getProperty("sun.jnu.encoding", System.getProperty("native.encoding", "")));

    private Utf8() {}

    /**
     * Refuses argument text this JVM would corrupt on the way to tmux.
     *
     * <p>Checked here rather than at each call site because every process this library starts is
     * built from one of these lists, and a value that reaches tmux mangled is indistinguishable
     * there from one the caller meant.
     *
     * @throws UnencodableTextException if an argument holds non-ASCII text and this JVM's native
     *     encoding is not UTF-8
     */
    public static void requireEncodableArguments(List<String> argv) {
        int refused = firstUnencodable(argv);
        if (refused >= 0) {
            throw unencodable(refused);
        }
    }

    /** Whether this JVM can hand every one of these to a child process intact. */
    public static boolean encodable(List<String> argv) {
        return firstUnencodable(argv) < 0;
    }

    /** The first code point this JVM would corrupt as an argument, or -1 when there is none. */
    private static int firstUnencodable(List<String> argv) {
        if (ARGUMENTS_ARE_UTF8) {
            return -1;
        }
        for (String argument : argv) {
            for (int index = 0; index < argument.length(); index++) {
                if (argument.charAt(index) > 0x7f) {
                    return argument.codePointAt(index);
                }
            }
        }
        return -1;
    }

    /**
     * The offending code point and nothing else. A tmux argument carries pane content, socket paths
     * and session names, none of which belongs in a message that reaches a log.
     */
    private static UnencodableTextException unencodable(int codePoint) {
        return new UnencodableTextException("this JVM encodes tmux arguments as "
                + System.getProperty("sun.jnu.encoding", System.getProperty("native.encoding", "an unnamed encoding"))
                + ", which cannot carry " + String.format(Locale.ROOT, "U+%04X", codePoint)
                + "; start the JVM in a UTF-8 locale (LC_ALL=C.UTF-8) to pass non-ASCII text to tmux");
    }

    static boolean argumentsAreUtf8(String encoding) {
        try {
            return StandardCharsets.UTF_8.equals(Charset.forName(encoding));
        } catch (RuntimeException unknown) {
            // An unsupported or malformed name is not UTF-8, and refusing is the safe reading.
            return false;
        }
    }

    /** Decodes valid UTF-8 and writes each malformed byte as a recoverable {@code \xNN} escape. */
    public static String backslashReplace(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        StringBuilder text = new StringBuilder(bytes.length);
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
        return text.toString();
    }

    /**
     * Decodes one byte stream that arrives in pieces cut anywhere, as {@link #backslashReplace}
     * decodes a whole one. A character cut between two pieces is held back and returned whole with
     * the later piece. One per stream, on one thread.
     */
    public static final class Stream {

        private final CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        private byte[] held = new byte[0];

        /** The text this piece completes, leaving a character it only starts for the next. */
        public String decode(byte[] piece) {
            byte[] bytes = piece;
            if (held.length > 0) {
                bytes = java.util.Arrays.copyOf(held, held.length + piece.length);
                System.arraycopy(piece, 0, bytes, held.length, piece.length);
            }
            ByteBuffer in = ByteBuffer.wrap(bytes);
            CharBuffer out = CharBuffer.allocate(bytes.length);
            StringBuilder text = new StringBuilder(bytes.length);
            decoder.reset();
            while (true) {
                // Not the end of input: an incomplete character at the end stays in the buffer.
                CoderResult result = decoder.decode(in, out, false);
                drainInto(out, text);
                if (result.isUnderflow()) {
                    break;
                }
                for (int offset = 0; offset < result.length(); offset++) {
                    escape(text, in.get(in.position() + offset));
                }
                in.position(in.position() + result.length());
            }
            held = java.util.Arrays.copyOfRange(bytes, in.position(), bytes.length);
            return text.toString();
        }
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
}
