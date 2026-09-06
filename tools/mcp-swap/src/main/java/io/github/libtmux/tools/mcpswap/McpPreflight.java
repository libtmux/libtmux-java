package io.github.libtmux.tools.mcpswap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class McpPreflight {
    private static final byte[] INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-swap-preflight","version":"1"}}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private McpPreflight() {}

    static void run(ServerSpec spec, Map<String, String> baseEnvironment, Path directory)
            throws IOException, InterruptedException {
        run(spec, baseEnvironment, directory, TIMEOUT, MAX_OUTPUT_BYTES);
    }

    static void run(ServerSpec spec, Map<String, String> baseEnvironment, Duration timeout, int maximumOutputBytes)
            throws IOException, InterruptedException {
        run(spec, baseEnvironment, null, timeout, maximumOutputBytes);
    }

    private static void run(
            ServerSpec spec,
            Map<String, String> baseEnvironment,
            @Nullable Path directory,
            Duration timeout,
            int maximumOutputBytes)
            throws IOException, InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("preflight timeout must be positive");
        }
        if (maximumOutputBytes < 1) {
            throw new IllegalArgumentException("preflight output limit must be positive");
        }
        var builder = new ProcessBuilder(command(spec));
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        builder.environment().clear();
        builder.environment().putAll(baseEnvironment);
        builder.environment().putAll(spec.environment());
        var process = builder.start();
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        var stdout = reader(process.getInputStream(), Stream.STDOUT, maximumOutputBytes, events);
        var stderr = reader(process.getErrorStream(), Stream.STDERR, maximumOutputBytes, events);
        var diagnostics = new ByteArrayOutputStream();
        var input = process.getOutputStream();
        try {
            input.write(INITIALIZE);
            input.flush();
            var deadline = System.nanoTime() + timeout.toNanos();
            var eof = 0;
            while (System.nanoTime() < deadline) {
                var wait = Math.min(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()), 25L);
                var event = events.poll(Math.max(wait, 1L), TimeUnit.MILLISECONDS);
                if (event == null) {
                    if (eof == 2 && !process.isAlive()) {
                        throw noResponse(process, diagnostics);
                    }
                    continue;
                }
                if (event.failure() != null) {
                    throw event.failure();
                }
                if (event.eof()) {
                    eof++;
                    if (eof == 2 && !process.isAlive()) {
                        throw noResponse(process, diagnostics);
                    }
                    continue;
                }
                if (event.stream() == Stream.STDERR) {
                    diagnostics.writeBytes(event.data());
                    continue;
                }
                if (initializeAccepted(event.data())) {
                    return;
                }
            }
            throw new IOException("no MCP response within " + timeout.toSeconds() + "s");
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // The process may already have closed its stdin; termination is authoritative.
            }
            terminate(process);
            stdout.join(Duration.ofSeconds(2));
            stderr.join(Duration.ofSeconds(2));
        }
    }

    private static List<String> command(ServerSpec spec) {
        List<String> command = new ArrayList<>(spec.arguments().size() + 1);
        command.add(spec.command());
        command.addAll(spec.arguments());
        return List.copyOf(command);
    }

    private static Thread reader(
            InputStream input, Stream stream, int maximumOutputBytes, BlockingQueue<Event> events) {
        return Thread.ofPlatform()
                .daemon()
                .name("mcp-swap-preflight-" + stream.name().toLowerCase(java.util.Locale.ROOT))
                .start(() -> {
                    var pending = new ByteArrayOutputStream();
                    var total = 0;
                    try (input) {
                        while (true) {
                            var value = input.read();
                            if (value < 0) {
                                if (pending.size() != 0) {
                                    events.put(Event.data(stream, pending.toByteArray()));
                                }
                                events.put(Event.eof(stream));
                                return;
                            }
                            total++;
                            if (total > maximumOutputBytes) {
                                events.put(Event.failure(
                                        stream,
                                        new IOException(
                                                "MCP " + stream.label + " exceeded " + maximumOutputBytes + " bytes")));
                                return;
                            }
                            pending.write(value);
                            if (value == '\n') {
                                events.put(Event.data(stream, pending.toByteArray()));
                                pending.reset();
                            }
                        }
                    } catch (IOException error) {
                        try {
                            events.put(Event.failure(stream, new IOException("read MCP " + stream.label, error)));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    private static boolean initializeAccepted(byte[] raw) throws IOException {
        final ObjectNode message;
        try {
            var parsed = JSON.readTree(ConfigCodec.decodeUtf8(raw, "MCP preflight stdout"));
            if (!(parsed instanceof ObjectNode object)) {
                return false;
            }
            message = object;
        } catch (JsonProcessingException invalid) {
            return false;
        }
        if (!message.path("id").canConvertToInt() || message.path("id").intValue() != 1) {
            return false;
        }
        if (message.has("error")) {
            var detail = message.path("error").path("message");
            throw new IOException(detail.isTextual() ? detail.textValue() : "initialize returned an MCP error");
        }
        var result = message.get("result");
        if (!message.path("jsonrpc").isTextual()
                || !message.path("jsonrpc").textValue().equals("2.0")
                || !(result instanceof ObjectNode object)
                || !object.path("protocolVersion").isTextual()
                || object.path("protocolVersion").textValue().isBlank()) {
            throw new IOException("initialize response is incomplete");
        }
        return true;
    }

    private static IOException noResponse(Process process, ByteArrayOutputStream diagnostics) {
        var text = diagnostics.toString(StandardCharsets.UTF_8).strip();
        if (!text.isEmpty()) {
            var lines = text.lines().toList();
            return new IOException(String.join("\n", lines.subList(Math.max(0, lines.size() - 3), lines.size())));
        }
        return new IOException("server exited with " + process.exitValue() + " without answering initialize");
    }

    private static void terminate(Process process) throws IOException, InterruptedException {
        Set<ProcessHandle> descendants = new HashSet<>();
        for (var attempt = 0; attempt < 4; attempt++) {
            process.descendants().forEach(descendants::add);
            descendants.stream()
                    .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (process.waitFor(250, TimeUnit.MILLISECONDS)
                    && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
                return;
            }
        }
        process.waitFor(1, TimeUnit.SECONDS);
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            throw new IOException("could not terminate the MCP preflight process tree");
        }
    }

    private enum Stream {
        STDOUT("stdout"),
        STDERR("stderr");

        private final String label;

        Stream(String label) {
            this.label = label;
        }
    }

    private static final class Event {
        private final Stream stream;
        private final byte[] data;
        private final boolean eof;
        private final @Nullable IOException failure;

        private Event(Stream stream, byte[] data, boolean eof, @Nullable IOException failure) {
            this.stream = stream;
            this.data = data.clone();
            this.eof = eof;
            this.failure = failure;
        }

        static Event data(Stream stream, byte[] data) {
            return new Event(stream, data, false, null);
        }

        static Event eof(Stream stream) {
            return new Event(stream, new byte[0], true, null);
        }

        static Event failure(Stream stream, IOException failure) {
            return new Event(stream, new byte[0], false, failure);
        }

        Stream stream() {
            return stream;
        }

        byte[] data() {
            return data.clone();
        }

        boolean eof() {
            return eof;
        }

        @Nullable
        IOException failure() {
            return failure;
        }
    }
}
