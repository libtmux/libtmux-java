package io.github.libtmux.control;

import io.github.libtmux.internal.Utf8;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Reads bounded physical lines from a control client's byte stream. */
final class ControlLineReader implements Closeable {

    private final BufferedInputStream source;
    private final int maxBytes;

    ControlLineReader(InputStream source, int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes is not positive");
        }
        this.source = new BufferedInputStream(source);
        this.maxBytes = maxBytes;
    }

    static final class Line {
        private final String text;
        private final int encodedBytes;
        private final byte[] bytes;

        Line(String text, int encodedBytes, byte[] bytes) {
            this.text = text;
            this.encodedBytes = encodedBytes;
            this.bytes = bytes.clone();
        }

        String text() {
            return text;
        }

        int encodedBytes() {
            return encodedBytes;
        }

        byte[] bytes() {
            return bytes.clone();
        }
    }

    @Nullable
    Line readLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 256));
        while (true) {
            int next = source.read();
            if (next < 0) {
                return bytes.size() == 0 ? null : line(bytes);
            }
            if (next == '\n') {
                return line(bytes);
            }
            if (bytes.size() == maxBytes) {
                throw new LimitExceeded(maxBytes);
            }
            bytes.write(next);
        }
    }

    private static Line line(ByteArrayOutputStream bytes) {
        byte[] encoded = bytes.toByteArray();
        int textLength = encoded.length;
        if (textLength > 0 && encoded[textLength - 1] == '\r') {
            textLength--;
        }
        return new Line(
                Utf8.backslashReplace(Arrays.copyOf(encoded, textLength)),
                encoded.length,
                Arrays.copyOf(encoded, textLength));
    }

    static final class LimitExceeded extends IOException {
        private static final long serialVersionUID = 1L;

        LimitExceeded(int limit) {
            super("control line exceeded the " + limit + " byte limit");
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
