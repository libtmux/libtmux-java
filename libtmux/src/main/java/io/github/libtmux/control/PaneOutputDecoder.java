package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Decodes UTF-8 incrementally per pane on the consuming thread.
 * Incomplete characters wait for the next chunk. A changed loss count resets
 * every pane; bytes on opposite sides of a gap never form a character.
 * Instances are not thread-safe. Call {@link #finish} at stream end to handle
 * an incomplete final character under the selected malformed-byte policy.
 */
public final class PaneOutputDecoder {
    private final CodingErrorAction malformed;
    private final Map<PaneId, byte[]> pending = new HashMap<>();
    private long loss;

    /** Accepts REPORT (throw) or REPLACE (U+FFFD) for malformed UTF-8. */
    public PaneOutputDecoder(CodingErrorAction malformed) {
        if (!CodingErrorAction.REPORT.equals(malformed) && !CodingErrorAction.REPLACE.equals(malformed)) {
            throw new IllegalArgumentException("malformed policy must be REPORT or REPLACE");
        }
        this.malformed = malformed;
    }

    /** Uses the loss snapshot returned atomically with an event. */
    public PaneOutput decode(EventSubscription.Delivery<PaneOutputBytes> delivery) throws CharacterCodingException {
        return decode(delivery.event(), delivery.droppedCount());
    }

    /**
     * Decodes a chunk after resetting partial characters when the loss count increases.
     * REPORT throws on malformed input and discards that pane's pending partial character.
     */
    public PaneOutput decode(PaneOutputBytes output, long droppedCount) throws CharacterCodingException {
        if (droppedCount < loss) {
            throw new IllegalArgumentException("loss count decreased");
        }
        if (droppedCount != loss) {
            reset();
            loss = droppedCount;
        }
        return decode(output, false);
    }

    /** Flushes an incomplete trailing character for one pane and releases its state. */
    public PaneOutput finish(PaneId pane) throws CharacterCodingException {
        return decode(new PaneOutputBytes(pane, new byte[0]), true);
    }

    /** Discards partial characters for all panes, for an externally reported gap. */
    public void reset() {
        pending.clear();
    }

    private PaneOutput decode(PaneOutputBytes output, boolean end) throws CharacterCodingException {
        byte[] prefix = pending.remove(output.pane());
        byte[] data = output.data();
        ByteBuffer input = ByteBuffer.allocate((prefix == null ? 0 : prefix.length) + data.length);
        if (prefix != null) {
            input.put(prefix);
        }
        input.put(data).flip();
        var decoder =
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(malformed).onUnmappableCharacter(malformed);
        CharBuffer text = CharBuffer.allocate(input.remaining() + 1);
        var result = decoder.decode(input, text, end);
        if (result.isError()) {
            result.throwException();
        }
        if (input.hasRemaining()) {
            byte[] remainder = new byte[input.remaining()];
            input.get(remainder);
            pending.put(output.pane(), remainder);
        }
        text.flip();
        return new PaneOutput(output.pane(), text.toString());
    }
}
