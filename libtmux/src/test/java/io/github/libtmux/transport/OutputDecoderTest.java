package io.github.libtmux.transport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Stdout uses tmux's LF framing and preserves carriage returns in values. Interior blank stdout
 * lines survive while trailing ones do not, and stderr loses every empty element.
 */
final class OutputDecoderTest {

    static Stream<Arguments> stdoutVectors() {
        return Stream.of(
                Arguments.of("invalid UTF-8", bytes(0x61, 0xff), List.of("a\\xff")),
                Arguments.of("invalid UTF-8 with valid text", bytes(0x61, 0xff, 0x62, 0x0a), List.of("a\\xffb")),
                Arguments.of("unterminated final line", "alpha".getBytes(UTF_8), List.of("alpha")),
                Arguments.of("repeated final newlines", "alpha\n\n\n".getBytes(UTF_8), List.of("alpha")),
                Arguments.of("interior blank line", "alpha\n\nbeta\n".getBytes(UTF_8), List.of("alpha", "", "beta")),
                Arguments.of("empty output", new byte[0], List.of()),
                Arguments.of("CRLF", bytes(0x61, 0x0d, 0x0a, 0x62, 0x0d, 0x0a), List.of("a\r", "b\r")),
                Arguments.of("lone CR", bytes(0x61, 0x0d, 0x62), List.of("a\rb")),
                Arguments.of("final CR", bytes(0x61, 0x0d), List.of("a\r")),
                Arguments.of("CR-only value", bytes(0x0d, 0x0a), List.of("\r")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stdoutVectors")
    void stdoutPreservesValuesBetweenLineFeeds(String behavior, byte[] raw, List<String> expected) {
        assertEquals(expected, OutputDecoder.stdoutLines(raw), behavior);
    }

    @Test
    void stderrDropsEveryEmptyElementWhereverItAppears() {
        assertEquals(List.of("x", "y"), OutputDecoder.stderrLines("\n\nx\n\ny\n".getBytes(UTF_8)));
        assertEquals(List.of(), OutputDecoder.stderrLines(new byte[0]));
        assertEquals(List.of("x", "y", "z"), OutputDecoder.stderrLines("\r\nx\r\ny\rz\r\n".getBytes(UTF_8)));
    }

    @Test
    void stdoutKeepsInteriorBlanksThatStderrWouldDrop() {
        byte[] raw = "alpha\n\nbeta\n".getBytes(UTF_8);

        assertEquals(List.of("alpha", "", "beta"), OutputDecoder.stdoutLines(raw));
        assertEquals(List.of("alpha", "beta"), OutputDecoder.stderrLines(raw));
    }

    @Test
    void everyReturnedListIsUnmodifiable() {
        List<String> lines = OutputDecoder.stdoutLines("alpha\n".getBytes(UTF_8));

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> lines.add("beta"), "decoded output is a value");
    }

    /**
     * A malformed sequence spanning a decoder buffer boundary must still be escaped byte by byte,
     * which a single-pass decoder with a fixed intermediate buffer gets wrong.
     */
    @Test
    void escapingSurvivesABufferBoundary() {
        byte[] raw = new byte[70_000];
        Arrays.fill(raw, (byte) 0x61);
        raw[69_000] = (byte) 0xff;

        String only = OutputDecoder.stdoutLines(raw).get(0);

        assertEquals(69_000, only.indexOf("\\xff"), "the escape lands at the malformed byte");
        assertEquals(70_003, only.length(), "one byte becomes four characters and nothing else changes");
    }

    private static byte[] bytes(int... values) {
        byte[] raw = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            raw[index] = (byte) values[index];
        }
        return raw;
    }
}
