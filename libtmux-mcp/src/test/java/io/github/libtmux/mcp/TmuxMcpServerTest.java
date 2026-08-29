package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.jackson.FilterJson;
import io.github.libtmux.jackson.LibTmuxModels;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.query.FilterExpr;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What the protocol layer tells a model, checked against what the library will actually accept.
 *
 * <p>A tool description is documentation a model follows literally. One that drifted from the schema
 * would send every model down the same wrong path, and would read perfectly while doing it.
 */
@ExtendWith(TmuxExtension.class)
final class TmuxMcpServerTest {

    @Test
    void theFilterExampleShownToAModelIsOneTheLibraryReads() {
        FilterExpr<Pane> parsed = FilterJson.readString(Catalog.EXAMPLE_FILTER, LibTmuxModels.pane());

        assertEquals(
                Pane_.command().startsWith("nvim").describe(),
                parsed.describe(),
                "the example must mean what it appears to mean");
    }

    /** And it has to select on a real server, not merely parse. */
    @Test
    void theFilterExampleSelectsAgainstRealTmux(Server server) {
        FilterExpr<Pane> parsed = FilterJson.readString(Catalog.EXAMPLE_FILTER, LibTmuxModels.pane());

        assertTrue(
                server.panes().stream().noneMatch(parsed),
                "the fixture runs a shell, so nothing should match a filter for nvim");
        assertEquals(1, server.panes().size(), "and the unfiltered listing still sees the pane");
    }

    @Test
    void closingAnEmbeddedMcpServerClosesItsWatcher(Server server) throws Exception {
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream client = new PipedOutputStream(input)) {
            client.flush();
            StdioServerTransportProvider transport = new StdioServerTransportProvider(
                    new JacksonMcpJsonMapper(new ObjectMapper()), input, new ByteArrayOutputStream());
            McpSyncServer mcp = TmuxMcpServer.serving(server, Safety.MUTATING, true, transport);
            try {
                assertTrue(await(() -> !server.clients().isEmpty()), "the watcher never attached");

                mcp.close();

                assertTrue(await(() -> server.clients().isEmpty()), "closing MCP left its watcher attached");
            } finally {
                mcp.close();
                for (var attached : server.clients()) {
                    server.cmd("detach-client", "-t", attached.name());
                }
            }
        }
    }

    @Test
    void brokenOutputEndsAStdioSessionEvenWhileInputRemainsOpen(Server server) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger endCalls = new AtomicInteger();
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream client = new PipedOutputStream(input);
                PrintStream output = new PrintStream(brokenOutput(), true, StandardCharsets.UTF_8)) {
            McpSyncServer mcp = TmuxMcpServer.overStdio(server, input, output, Safety.MUTATING, false, () -> {
                endCalls.incrementAndGet();
                ended.countDown();
            });
            try {
                String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                        + "\"protocolVersion\":\"" + ProtocolVersions.MCP_2025_11_25
                        + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}\n";
                client.write(initialize.getBytes(StandardCharsets.UTF_8));
                client.flush();

                assertTrue(ended.await(3, TimeUnit.SECONDS), "stdout failed but the protocol session stayed alive");
            } finally {
                mcp.close();
            }
            assertEquals(1, endCalls.get(), "one failed session reported more than one end");
        }
    }

    @Test
    void oversizedStdioInputEndsTheSessionBeforeNewline(Server server) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        PipedInputStream input = new PipedInputStream();
        SessionLifetime lifetime = new SessionLifetime(ended::countDown);
        try (PipedOutputStream client = new PipedOutputStream(input)) {
            var transport = new StdioServerTransportProvider(
                    new JacksonMcpJsonMapper(new ObjectMapper()), input, new ByteArrayOutputStream(), 64);
            McpSyncServer mcp = TmuxMcpServer.serving(server, Safety.MUTATING, lifetime.observe(transport));
            try {
                client.write("x".repeat(65).getBytes(StandardCharsets.UTF_8));
                client.flush();

                assertTrue(ended.await(3, TimeUnit.SECONDS), "oversized input kept buffering without a newline");
            } finally {
                mcp.close();
            }
        }
    }

    @Test
    void callbackFailureDoesNotHideProtocolOutputFailure() {
        IOException outputFailure = new IOException("client stopped reading");
        IllegalStateException callbackFailure = new IllegalStateException("session-end callback failed");
        OutputStream output = new SessionLifetime(() -> {
                    throw callbackFailure;
                })
                .observe(new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw outputFailure;
                    }
                });

        IOException thrown = assertThrows(IOException.class, () -> output.write(0));

        assertSame(outputFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(callbackFailure, thrown.getSuppressed()[0]);
    }

    private static OutputStream brokenOutput() {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client stopped reading");
            }
        };
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
