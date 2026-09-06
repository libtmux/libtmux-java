package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;

/**
 * What the protocol layer tells a model, checked against what the library will actually accept.
 *
 * <p>A tool description is documentation a model follows literally. One that drifted from the schema
 * would send every model down the same wrong path, and would read perfectly while doing it.
 */
@ExtendWith(TmuxExtension.class)
final class TmuxMcpServerTest {

    @Test
    void readBatchBoundsTheCompleteJsonRpcLine() throws Exception {
        String payload = "x".repeat(140_000);
        TmuxTransport environment = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(0, List.of("VALUE=" + payload), List.of());
            }

            @Override
            public void close() {}
        };
        ToolSurface surface = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "call_read_tools_batch"));
        String requestId = "batch-response";
        byte[] request = (Answers.JSON.writeValueAsString(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                requestId,
                                "method",
                                "tools/call",
                                "params",
                                Map.of(
                                        "name",
                                        "call_read_tools_batch",
                                        "arguments",
                                        Map.of(
                                                "operations",
                                                List.of(
                                                        Map.of("tool", "show_environment"),
                                                        Map.of("tool", "show_environment"))))))
                        + "\n")
                .getBytes(StandardCharsets.UTF_8);
        WireOutput output = new WireOutput();

        try (Server server = Server.using(ServerConfig.builder().build(), environment);
                PipedInputStream input = new PipedInputStream();
                PipedOutputStream client = new PipedOutputStream(input)) {
            McpSyncServer mcp = TmuxMcpServer.overStdio(server, input, output, surface, () -> {});
            try {
                client.write(initialize());
                client.flush();
                assertTrue(output.first.await(3, TimeUnit.SECONDS), "initialization did not answer");

                client.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                        .getBytes(StandardCharsets.UTF_8));
                client.write(request);
                client.flush();
                assertTrue(output.second.await(5, TimeUnit.SECONDS), "read batch did not answer");
            } finally {
                mcp.close();
            }
        }

        String response = output.lines().stream()
                .filter(line -> line.contains(requestId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("read batch response is absent"));
        assertTrue(
                response.getBytes(StandardCharsets.UTF_8).length + 1 <= 1_000_000,
                "the complete JSON-RPC response, including its newline, exceeds 1,000,000 bytes");

        var result = Answers.JSON.readTree(response).path("result").path("structuredContent");
        assertEquals(2, result.path("results").size(), "an executed row was dropped");
        boolean explicitlyTruncated = false;
        for (var row : result.path("results")) {
            assertEquals(true, row.path("success").asBoolean());
            if (row.path("resultTruncated").asBoolean()) {
                explicitlyTruncated = true;
                assertTrue(row.path("result").isNull());
            }
        }
        assertTrue(explicitlyTruncated, "the oversized nested payloads were not marked as truncated");
        assertEquals(true, result.path("truncated").asBoolean());
        assertTrue(result.path("truncatedBytes").asInt() > 0);
    }

    @Test
    void oversizedRequestIdFailsBeforeToolDispatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TmuxTransport environment = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                calls.incrementAndGet();
                return new CommandResult(0, List.of("VALUE=kept"), List.of());
            }

            @Override
            public void close() {}
        };
        ToolSurface surface =
                ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "show_environment"));
        String acceptedId = "i".repeat(StdioRequestFilter.REQUEST_ID_MAX_BYTES - 2);
        assertEquals(StdioRequestFilter.REQUEST_ID_MAX_BYTES, Answers.JSON.writeValueAsBytes(acceptedId).length);
        WireOutput output = new WireOutput();

        try (Server server = Server.using(ServerConfig.builder().build(), environment);
                PipedInputStream input = new PipedInputStream();
                PipedOutputStream client = new PipedOutputStream(input)) {
            McpSyncServer mcp = TmuxMcpServer.overStdio(server, input, output, surface, () -> {});
            try {
                client.write(initialize());
                client.flush();
                assertTrue(output.first.await(3, TimeUnit.SECONDS), "initialization did not answer");
                calls.set(0);

                client.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                        .getBytes(StandardCharsets.UTF_8));
                client.write(toolCall(acceptedId));
                client.flush();
                assertTrue(output.second.await(5, TimeUnit.SECONDS), "near-bound request ID did not answer");
                assertEquals(1, calls.get(), "near-bound request ID did not dispatch exactly once");
                assertEquals(
                        acceptedId,
                        Answers.JSON.readTree(output.lines().get(1)).path("id").textValue(),
                        "near-bound request ID did not round trip");

                client.write(toolCall("i".repeat(1_000_000)));
                client.flush();
                assertTrue(output.awaitLines(3, 5, TimeUnit.SECONDS), "oversized request ID did not answer");
            } finally {
                mcp.close();
            }
        }

        String rejectedLine = output.lines().get(2);
        var rejected = Answers.JSON.readTree(rejectedLine);
        boolean idNull = rejected.has("id") && rejected.path("id").isNull();
        int code = rejected.path("error").path("code").asInt();
        int wireBytes = rejectedLine.getBytes(StandardCharsets.UTF_8).length + 1;
        assertTrue(
                idNull && code == -32600 && wireBytes <= 1_000_000 && calls.get() == 1,
                () -> "oversized response = (" + wireBytes + " bytes, id null " + idNull + ", code " + code + ", calls "
                        + calls.get() + ")");
    }

    @Test
    void brokenOutputEndsAStdioSessionEvenWhileInputRemainsOpen(Server server) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger endCalls = new AtomicInteger();
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream client = new PipedOutputStream(input);
                PrintStream output = new PrintStream(brokenOutput(), true, StandardCharsets.UTF_8)) {
            McpSyncServer mcp = TmuxMcpServer.overStdio(server, input, output, ToolSurface.defaults(), () -> {
                endCalls.incrementAndGet();
                ended.countDown();
            });
            try {
                client.write(initialize());
                client.flush();

                assertTrue(ended.await(3, TimeUnit.SECONDS), "stdout failed but the protocol session stayed alive");
            } finally {
                mcp.close();
            }
            assertEquals(1, endCalls.get(), "one failed session reported more than one end");
        }
    }

    @Test
    void closingAStdioServerUnblocksItsInputReader(Server server) throws Exception {
        BlockingInput input = new BlockingInput();
        McpSyncServer mcp =
                TmuxMcpServer.overStdio(server, input, new ByteArrayOutputStream(), ToolSurface.defaults(), () -> {});
        try {
            assertTrue(input.reading.await(3, TimeUnit.SECONDS), "the protocol reader never started");

            mcp.close();

            assertTrue(input.closed.await(3, TimeUnit.SECONDS), "closing MCP left its input stream open");
            assertTrue(input.readEnded.await(3, TimeUnit.SECONDS), "closing MCP left its input reader blocked");
        } finally {
            input.close();
            mcp.close();
        }
    }

    @Test
    void failedStartupClosesAnyAcceptedTransport(Server server) throws Exception {
        AtomicInteger closes = new AtomicInteger();
        IllegalStateException startupFailure = new IllegalStateException("session factory failed");
        McpServerTransportProvider transport = new McpServerTransportProvider() {
            @Override
            public void setSessionFactory(McpServerSession.Factory factory) {
                throw startupFailure;
            }

            @Override
            public Mono<Void> notifyClients(String method, Object params) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> closeGracefully() {
                return Mono.empty();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> TmuxMcpServer.serving(server, ToolSurface.defaults(), transport));

        assertSame(startupFailure, thrown);
        assertEquals(1, closes.get(), "accepted transport was not closed exactly once");
    }

    @Test
    void oversizedStdioInputEndsTheSessionBeforeNewline(Server server) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        PipedInputStream input = new PipedInputStream();
        SessionLifetime lifetime = new SessionLifetime(ended::countDown);
        try (PipedOutputStream client = new PipedOutputStream(input)) {
            var transport = new StdioServerTransportProvider(
                    new JacksonMcpJsonMapper(new ObjectMapper()), input, new ByteArrayOutputStream(), 64);
            McpSyncServer mcp = TmuxMcpServer.serving(server, ToolSurface.defaults(), lifetime.observe(transport));
            try {
                client.write("x".repeat(65).getBytes(StandardCharsets.UTF_8));
                client.flush();

                assertTrue(ended.await(3, TimeUnit.SECONDS), "oversized input kept buffering without a newline");
            } finally {
                mcp.close();
            }
        }
    }

    private static OutputStream brokenOutput() {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client stopped reading");
            }
        };
    }

    private static byte[] initialize() {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + ProtocolVersions.MCP_2025_11_25
                + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}\n";
        return request.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] toolCall(String id) throws IOException {
        return (Answers.JSON.writeValueAsString(Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                id,
                                "method",
                                "tools/call",
                                "params",
                                Map.of("name", "show_environment", "arguments", Map.of())))
                        + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class BlockingInput extends java.io.InputStream {

        private final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch readEnded = new CountDownLatch(1);

        @Override
        public int read() {
            return awaitClose();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return awaitClose();
        }

        private int awaitClose() {
            reading.countDown();
            try {
                closed.await();
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } finally {
                readEnded.countDown();
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class WireOutput extends OutputStream {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CountDownLatch first = new CountDownLatch(1);
        private final CountDownLatch second = new CountDownLatch(1);
        private int lines;

        @Override
        public synchronized void write(int value) {
            bytes.write(value);
            count(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            bytes.write(values, offset, length);
            for (int index = offset; index < offset + length; index++) {
                count(values[index]);
            }
        }

        synchronized List<String> lines() {
            return bytes.toString(StandardCharsets.UTF_8).lines().toList();
        }

        synchronized boolean awaitLines(int expected, long timeout, TimeUnit unit) throws InterruptedException {
            long remaining = unit.toNanos(timeout);
            long end = System.nanoTime() + remaining;
            while (lines < expected && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
                remaining = end - System.nanoTime();
            }
            return lines >= expected;
        }

        private void count(int value) {
            if (value != '\n') {
                return;
            }
            lines++;
            notifyAll();
            if (lines == 1) {
                first.countDown();
            } else if (lines == 2) {
                second.countDown();
            }
        }
    }
}
