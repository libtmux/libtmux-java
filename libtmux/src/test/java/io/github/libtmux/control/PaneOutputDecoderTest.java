package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.PaneId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PaneOutputDecoderTest {

    @Test
    void everyUtf8SplitAndMalformedByteSurvivesOnItsOwnPane() {
        for (String text : new String[] {"é", "€", "😀"}) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (int split = 1; split < bytes.length; split++) {
                var decoder = new PaneOutputDecoder(2);
                assertEquals("", decoder.decode(output("%1", bytes, 0, split)));
                assertEquals("\\xa9", decoder.decode(output("%2", new byte[] {(byte) 0xa9}, 0, 1)));
                assertEquals(text, decoder.decode(output("%1", bytes, split, bytes.length)));
                assertEquals(Map.of(), decoder.finish());
            }
        }
    }

    @Test
    void aGapOrDecoderEvictionCannotJoinBytesAcrossLostOutput() {
        var decoder = new PaneOutputDecoder(1);
        byte[] bytes = {(byte) 0xc3, (byte) 0xa9};
        assertEquals("", decoder.decode(output("%1", bytes, 0, 1)));
        decoder.reset();
        assertEquals("\\xa9", decoder.decode(output("%1", bytes, 1, 2)));
        assertEquals(1, decoder.resetCount());

        assertEquals("", decoder.decode(output("%1", bytes, 0, 1)));
        assertEquals("", decoder.decode(output("%2", bytes, 0, 1)));
        assertEquals(2, decoder.resetCount());
        assertEquals("\\xa9", decoder.decode(output("%1", bytes, 1, 2)));
        assertEquals(Map.of(new PaneId("%2"), "\\xc3"), decoder.finish());
        assertEquals(Map.of(), decoder.finish());
        assertThrows(IllegalArgumentException.class, () -> new PaneOutputDecoder(0));
    }

    private static PaneOutput output(String pane, byte[] bytes, int start, int end) {
        return new PaneOutput(new PaneId(pane), "unused predecoded text", ByteBuffer.wrap(bytes, start, end - start));
    }
}
