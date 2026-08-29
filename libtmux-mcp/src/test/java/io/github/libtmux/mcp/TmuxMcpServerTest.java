package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
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
