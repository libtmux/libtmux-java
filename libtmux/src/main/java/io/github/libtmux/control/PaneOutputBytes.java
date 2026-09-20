package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Preserves one pane output chunk without interpreting its character encoding. */
public final class PaneOutputBytes {
    private final PaneId pane;
    private final byte[] data;

    public PaneOutputBytes(PaneId pane, byte[] data) {
        this.pane = Objects.requireNonNull(pane);
        this.data = data.clone();
    }

    public PaneId pane() {
        return pane;
    }

    /** Returns a defensive copy of the unescaped terminal bytes. */
    public byte[] data() {
        return data.clone();
    }

    public int size() {
        return data.length;
    }

    static PaneOutputBytes parse(byte[] line) {
        int start = "%output ".length();
        int end = start;
        while (end < line.length && line[end] != ' ') {
            end++;
        }
        if (end == line.length) {
            throw new IllegalArgumentException("output notification has no payload separator");
        }
        PaneId pane = new PaneId(new String(line, start, end - start, StandardCharsets.US_ASCII));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(line.length - end - 1);
        for (int i = end + 1; i < line.length; i++) {
            if (line[i] == '\\'
                    && i + 3 < line.length
                    && octal(line[i + 1])
                    && octal(line[i + 2])
                    && octal(line[i + 3])) {
                bytes.write((line[i + 1] - '0') * 64 + (line[i + 2] - '0') * 8 + line[i + 3] - '0');
                i += 3;
            } else {
                bytes.write(line[i]);
            }
        }
        return new PaneOutputBytes(pane, bytes.toByteArray());
    }

    private static boolean octal(byte value) {
        return value >= '0' && value <= '7';
    }

    @Override
    public String toString() {
        return "PaneOutputBytes[" + pane + ", bytes=" + data.length + "]";
    }
}
