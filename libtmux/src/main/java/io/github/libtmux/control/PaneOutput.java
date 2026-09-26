package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Terminal output tmux pushed without being asked.
 *
 * <p>tmux cuts a pane's output into pieces by byte count, not by character. {@code bytes} is
 * exactly what one piece carried, so a pane's pieces concatenate to what it wrote. {@code data} is
 * the same output as text, decoded per pane: a character cut between two pieces arrives whole with
 * the later one, and a byte that is not UTF-8 is written {@code \xHH}.
 *
 * @param pane the pane that produced it
 * @param data this piece as text
 * @param bytes this piece exactly, read-only
 */
public record PaneOutput(PaneId pane, String data, ByteBuffer bytes) {

    /** Copies {@code bytes}, from its position to its limit, so the record cannot change. */
    public PaneOutput {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(data, "data");
        ByteBuffer copy =
                ByteBuffer.allocate(Objects.requireNonNull(bytes, "bytes").remaining());
        copy.put(bytes.duplicate()).flip();
        bytes = copy.asReadOnlyBuffer();
    }

    /** Output whose bytes are {@code data} in UTF-8. */
    public PaneOutput(PaneId pane, String data) {
        this(pane, data, ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** This piece's bytes, read-only, positioned at the first. */
    @Override
    public ByteBuffer bytes() {
        return bytes.duplicate();
    }

    /** Identifies the pane only: the data is terminal content. */
    @Override
    public String toString() {
        return "PaneOutput[" + pane + ", characters=" + data.length() + ", bytes=" + bytes.remaining() + "]";
    }
}
