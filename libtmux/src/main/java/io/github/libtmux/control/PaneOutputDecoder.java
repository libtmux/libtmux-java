package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import io.github.libtmux.internal.Utf8;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.annotations.jvm.ReadOnly;

/**
 * Decodes a subscriber's raw UTF-8 output independently of other subscribers.
 * Incomplete characters wait for the same pane's next chunk; malformed bytes
 * become {@code \xHH} escapes. Call {@link #reset} after each subscription gap
 * and {@link #finish} at termination. Confine an instance to one reader.
 */
public final class PaneOutputDecoder {
    private final int capacity;
    private final LinkedHashMap<PaneId, Utf8.Stream> pending = new LinkedHashMap<>();
    private long resets;

    /**
     * Bounds panes holding an incomplete character, each at most three bytes.
     * A new partial pane evicts the oldest partial pane and increments {@link #resetCount}.
     *
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public PaneOutputDecoder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Decodes the preserved bytes, ignoring the client's predecoded text. */
    public String decode(PaneOutput output) {
        Utf8.Stream decoder = pending.remove(output.pane());
        if (decoder == null) {
            decoder = new Utf8.Stream();
        }
        ByteBuffer buffer = output.bytes();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String text = decoder.decode(bytes);
        if (decoder.hasPending()) {
            if (pending.size() == capacity) {
                pending.pollFirstEntry();
                resets++;
            }
            pending.put(output.pane(), decoder);
        }
        return text;
    }

    /** Discards all partial characters after a gap and counts that reset. */
    public void reset() {
        pending.clear();
        resets++;
    }

    /** The monotonic count of gap resets and partial-pane evictions. */
    public long resetCount() {
        return resets;
    }

    /** Escapes remaining partial bytes by pane, releases them, and returns an immutable map. */
    public @ReadOnly Map<PaneId, String> finish() {
        Map<PaneId, String> trailing = new LinkedHashMap<>();
        pending.forEach((pane, decoder) -> trailing.put(pane, decoder.finish()));
        pending.clear();
        return Map.copyOf(trailing);
    }
}
