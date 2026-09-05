package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
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
}
