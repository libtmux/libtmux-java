package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.jackson.FilterJson;
import io.github.libtmux.jackson.LibTmuxModels;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.query.FilterExpr;
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
}
