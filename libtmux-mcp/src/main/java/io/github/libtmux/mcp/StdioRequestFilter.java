package io.github.libtmux.mcp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Rejects request IDs that would consume the bounded response line. */
final class StdioRequestFilter extends InputStream {

    static final int REQUEST_ID_MAX_BYTES = 512 * 1024;
    // Preserve the pinned SDK transport's line ceiling while filtering before it.
    private static final int INPUT_MAX_CHARS = 16 * 1024 * 1024;
    private static final byte[] OVERSIZED_ID_ERROR = ("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{"
                    + "\"code\":-32600,\"message\":\"request id exceeds "
                    + REQUEST_ID_MAX_BYTES
                    + " bytes\"}}\n")
            .getBytes(StandardCharsets.UTF_8);

    private final BufferedReader input;
    private final OutputStream output;
    private final byte[] one = new byte[1];
    private byte[] pending = new byte[0];
    private int offset;

    StdioRequestFilter(InputStream input, OutputStream output) {
        this.input = new BufferedReader(new InputStreamReader(Objects.requireNonNull(input), StandardCharsets.UTF_8));
        this.output = Objects.requireNonNull(output);
    }

    @Override
    public int read() throws IOException {
        int read = read(one, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    @Override
    public int read(byte[] bytes, int destination, int length) throws IOException {
        Objects.checkFromIndexSize(destination, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        while (offset == pending.length && !refill()) {
            return -1;
        }
        int copied = Math.min(length, pending.length - offset);
        System.arraycopy(pending, offset, bytes, destination, copied);
        offset += copied;
        return copied;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private boolean refill() throws IOException {
        while (true) {
            String line = readLine();
            if (line == null) {
                return false;
            }
            if (oversizedRequestId(line)) {
                rejectOversizedRequestId();
                continue;
            }
            pending = (line + "\n").getBytes(StandardCharsets.UTF_8);
            offset = 0;
            return true;
        }
    }

    private @Nullable String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                return line.toString();
            }
            if (value == '\r') {
                input.mark(1);
                int next = input.read();
                if (next != '\n' && next != -1) {
                    input.reset();
                }
                return line.toString();
            }
            if (line.length() >= INPUT_MAX_CHARS) {
                throw new IOException("JSON-RPC input exceeds " + INPUT_MAX_CHARS + " characters");
            }
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static boolean oversizedRequestId(String line) {
        try {
            JsonNode message = Answers.JSON.readTree(line);
            if (message == null
                    || !message.isObject()
                    || !message.path("method").isTextual()) {
                return false;
            }
            JsonNode id = message.get("id");
            if (id == null || !(id.isTextual() || id.isIntegralNumber())) {
                return false;
            }
            return Answers.JSON.writeValueAsBytes(id).length > REQUEST_ID_MAX_BYTES;
        } catch (JacksonException ignored) {
            return false;
        }
    }

    private void rejectOversizedRequestId() throws IOException {
        synchronized (output) {
            output.write(OVERSIZED_ID_ERROR);
            output.flush();
        }
    }
}
