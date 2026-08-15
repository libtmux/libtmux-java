package com.git_pull.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Pane_;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.jackson.FilterJson;
import com.git_pull.libtmux.jackson.LibTmuxModels;
import com.git_pull.libtmux.junit5.TmuxExtension;
import com.git_pull.libtmux.query.FilterExpr;
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
        FilterExpr<Pane> parsed = FilterJson.readString(TmuxMcpServer.EXAMPLE_FILTER, LibTmuxModels.pane());

        assertEquals(
                Pane_.command().startsWith("nvim").describe(),
                parsed.describe(),
                "the example must mean what it appears to mean");
    }

    /** And it has to select on a real server, not merely parse. */
    @Test
    void theFilterExampleSelectsAgainstRealTmux(Server server) {
        FilterExpr<Pane> parsed = FilterJson.readString(TmuxMcpServer.EXAMPLE_FILTER, LibTmuxModels.pane());

        assertTrue(
                server.panes().stream().noneMatch(parsed),
                "the fixture runs a shell, so nothing should match a filter for nvim");
        assertEquals(1, server.panes().size(), "and the unfiltered listing still sees the pane");
    }
}
