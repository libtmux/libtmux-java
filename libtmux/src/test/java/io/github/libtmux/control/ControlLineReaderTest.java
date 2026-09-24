package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

final class ControlLineReaderTest {

    @Test
    void aLineCannotGrowPastItsByteLimit() throws Exception {
        byte[] input = "12345\n".getBytes(StandardCharsets.UTF_8);
        try (var lines = new ControlLineReader(new ByteArrayInputStream(input), 4)) {
            assertThrows(IOException.class, lines::readLine);
        }
    }

    @Test
    void lineEndingsAreRemovedButCounted() throws Exception {
        byte[] input = "one\r\ntwo".getBytes(StandardCharsets.UTF_8);
        try (var lines = new ControlLineReader(new ByteArrayInputStream(input), 8)) {
            assertLine("one", 4, new byte[] {'o', 'n', 'e'}, lines.readLine());
            assertLine("two", 3, new byte[] {'t', 'w', 'o'}, lines.readLine());
            assertEquals(null, lines.readLine());
        }
    }

    @Test
    void malformedUtf8RemainsRecoverableAsItsOriginalByte() throws Exception {
        byte[] input = {'a', (byte) 0xff, '\n'};

        try (var lines = new ControlLineReader(new ByteArrayInputStream(input), 8)) {
            assertLine("a\\xff", 2, new byte[] {'a', (byte) 0xff}, lines.readLine());
        }
    }

    private static void assertLine(String text, int encodedBytes, byte[] bytes, ControlLineReader.@Nullable Line line) {
        assertEquals(text, Objects.requireNonNull(line).text());
        assertEquals(encodedBytes, line.encodedBytes());
        assertArrayEquals(bytes, line.bytes());
    }
}
