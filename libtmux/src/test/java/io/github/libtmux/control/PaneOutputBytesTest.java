package io.github.libtmux.control;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

import io.github.libtmux.PaneId;
import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PaneOutputBytesTest {
    private static final PaneId FIRST = new PaneId("%1");
    private static final PaneId SECOND = new PaneId("%2");

    @Test
    void rawLinesPreserveSplitAndInvalidBytesBeforeTextDecoding() throws Exception {
        byte[] raw = {
            '%',
            'o',
            'u',
            't',
            'p',
            'u',
            't',
            ' ',
            '%',
            '1',
            ' ',
            (byte) 0xc3,
            '\n',
            '%',
            'o',
            'u',
            't',
            'p',
            'u',
            't',
            ' ',
            '%',
            '1',
            ' ',
            (byte) 0xa9,
            (byte) 0xff,
            '\n'
        };
        var decoder = new PaneOutputDecoder(CodingErrorAction.REPLACE);
        try (var reader = new ControlLineReader(new ByteArrayInputStream(raw), 100)) {
            var first = PaneOutputBytes.parse(requireNonNull(reader.readLine()).bytes());
            var second = PaneOutputBytes.parse(requireNonNull(reader.readLine()).bytes());
            assertArrayEquals(new byte[] {(byte) 0xc3}, first.data());
            assertArrayEquals(new byte[] {(byte) 0xa9, (byte) 0xff}, second.data());
            assertEquals("", decoder.decode(first, 0).data());
            assertEquals("é�", decoder.decode(second, 0).data());
        }
    }

    @Test
    void everyUtf8SplitBoundaryDecodesWithoutReplacement() throws Exception {
        for (String expected : java.util.List.of("é", "€", "😀")) {
            byte[] encoded = expected.getBytes(StandardCharsets.UTF_8);
            for (int split = 1; split < encoded.length; split++) {
                var decoder = new PaneOutputDecoder(CodingErrorAction.REPORT);
                assertEquals(
                        "",
                        decoder.decode(new PaneOutputBytes(FIRST, java.util.Arrays.copyOf(encoded, split)), 0)
                                .data());
                assertEquals(
                        expected,
                        decoder.decode(
                                        new PaneOutputBytes(
                                                FIRST, java.util.Arrays.copyOfRange(encoded, split, encoded.length)),
                                        0)
                                .data());
            }
        }
    }

    @Test
    void escapesAreBytesAndPayloadCopiesCannotMutateAnEvent() {
        var output = PaneOutputBytes.parse("%output %1 \\303\\251\\377\\134".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0xa9, (byte) 0xff, '\\'}, output.data());
        byte[] source = {(byte) 0xff};
        var immutable = new PaneOutputBytes(FIRST, source);
        source[0] = 0;
        immutable.data()[0] = 0;
        assertEquals((byte) 0xff, immutable.data()[0]);
    }

    @Test
    void panesKeepIndependentPartialCharactersAndGapsResetThem() throws Exception {
        var decoder = new PaneOutputDecoder(CodingErrorAction.REPLACE);
        assertEquals("", decoder.decode(bytes(FIRST, 0xc3), 0).data());
        assertEquals("", decoder.decode(bytes(SECOND, 0xe2, 0x82), 0).data());
        assertEquals("é", decoder.decode(bytes(FIRST, 0xa9), 0).data());
        assertEquals("€", decoder.decode(bytes(SECOND, 0xac), 0).data());
        decoder.decode(bytes(FIRST, 0xc3), 0);
        decoder.decode(bytes(SECOND, 0xc3), 0);
        assertEquals("�", decoder.decode(bytes(FIRST, 0xa9), 1).data());
        assertEquals("�", decoder.decode(bytes(SECOND, 0xa9), 1).data());
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(bytes(FIRST, 65), 0));
    }

    @Test
    void malformedPolicyAlsoAppliesAtEndOfStream() throws Exception {
        var strict = new PaneOutputDecoder(CodingErrorAction.REPORT);
        assertThrows(CharacterCodingException.class, () -> strict.decode(bytes(FIRST, 0xff), 0));
        strict.decode(bytes(FIRST, 0xe2), 0);
        assertThrows(CharacterCodingException.class, () -> strict.finish(FIRST));
        var replacing = new PaneOutputDecoder(CodingErrorAction.REPLACE);
        replacing.decode(bytes(FIRST, 0xe2), 0);
        assertEquals("�", replacing.finish(FIRST).data());
        assertEquals("", replacing.finish(FIRST).data());
    }

    @Test
    void eventAndByteBoundsReportAtomicMonotonicLossAndFirstTermination() throws Exception {
        var events = new EventSubscription<PaneOutputBytes>(2, 3, PaneOutputBytes::size, ignored -> {});
        try {
            events.offer(bytes(FIRST, 1, 2));
            events.offer(bytes(FIRST, 3, 4));
            assertEquals(2, events.retainedBytes());
            var next = events.nextDelivery(Duration.ZERO).orElseThrow();
            assertEquals(1, next.droppedCount());
            assertEquals(2, next.droppedBytes());
            assertEquals(0, events.retainedBytes());
            events.offer(bytes(FIRST, 1));
            events.offer(bytes(FIRST, 2));
            events.offer(bytes(FIRST, 3));
            assertEquals(2, events.droppedCount());
            events.offer(bytes(FIRST, 1, 2, 3, 4));
            assertEquals(5, events.droppedCount());
            assertEquals(9, events.droppedBytes());
            assertEquals(0, events.retainedBytes());
            assertTrue(events.nextDelivery(Duration.ZERO).isEmpty());
            var failure = new IllegalStateException("original");
            events.end(new EventSubscription.Termination(
                    EventSubscription.EndReason.PROTOCOL_FAILURE, Optional.of(failure)));
            events.close();
            assertEquals(
                    EventSubscription.EndReason.PROTOCOL_FAILURE,
                    events.termination().orElseThrow().reason());
            assertSame(failure, events.termination().orElseThrow().cause().orElseThrow());
        } finally {
            events.close();
        }
    }

    @Test
    void remoteTerminationPreservesFinalOutputAndIntentionalCloseDiscardsIt() throws Exception {
        try (var events = new EventSubscription<PaneOutputBytes>(2, 16, PaneOutputBytes::size, ignored -> {})) {
            events.offer(bytes(FIRST, 65));
            events.end(new EventSubscription.Termination(EventSubscription.EndReason.CONTROL_EXIT, Optional.empty()));
            assertTrue(events.isClosed());
            assertEquals(1, events.retainedBytes());
            var delivery = events.nextDelivery(Duration.ZERO).orElseThrow();
            assertArrayEquals(new byte[] {65}, delivery.event().data());
            assertEquals(0, delivery.droppedCount());
            assertEquals(0, delivery.droppedBytes());
            assertTrue(events.nextDelivery(Duration.ZERO).isEmpty());
        }
        var events = new EventSubscription<PaneOutputBytes>(2, 16, PaneOutputBytes::size, ignored -> {});
        try {
            events.offer(bytes(FIRST, 65));
            events.end(new EventSubscription.Termination(EventSubscription.EndReason.CONTROL_EXIT, Optional.empty()));
            events.close();
            assertEquals(0, events.retainedBytes());
            assertTrue(events.nextDelivery(Duration.ZERO).isEmpty());
            assertEquals(
                    EventSubscription.EndReason.CONTROL_EXIT,
                    events.termination().orElseThrow().reason());
        } finally {
            events.close();
        }
    }

    private static PaneOutputBytes bytes(PaneId pane, int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new PaneOutputBytes(pane, bytes);
    }
}
