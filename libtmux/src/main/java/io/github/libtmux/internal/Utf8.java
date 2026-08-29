package io.github.libtmux.internal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Byte-preserving UTF-8 decoding shared by process and control transports. */
public final class Utf8 {

    private Utf8() {}

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
