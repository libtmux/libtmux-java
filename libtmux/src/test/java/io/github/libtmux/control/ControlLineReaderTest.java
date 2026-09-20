package io.github.libtmux.control;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            var one = requireNonNull(lines.readLine());
            assertEquals("one", one.text());
            assertEquals(4, one.encodedBytes());
            assertEquals("two", requireNonNull(lines.readLine()).text());
            assertEquals(null, lines.readLine());
        }
    }

    @Test
    void malformedUtf8RemainsRecoverableAsItsOriginalByte() throws Exception {
        byte[] input = {'a', (byte) 0xff, '\n'};

        try (var lines = new ControlLineReader(new ByteArrayInputStream(input), 8)) {
            assertEquals("a\\xff", requireNonNull(lines.readLine()).text());
        }
    }
}
