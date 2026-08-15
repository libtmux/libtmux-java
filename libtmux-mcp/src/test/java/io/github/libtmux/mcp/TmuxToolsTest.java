package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.jackson.FilterJson;
import io.github.libtmux.jackson.LibTmuxModels;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.query.FilterExpr;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What a model can do to tmux, against real tmux.
 *
 * <p>Every tool addresses a pane by id rather than by position, because a model works from a listing
 * it read some time ago and pane indexes move as neighbours come and go. These cases check that a
 * stale position cannot silently become a live target.
 */
@ExtendWith(TmuxExtension.class)
final class TmuxToolsTest {

    @Test
    void sessionsAreListedWithTheirWindows(Server server) {
        TmuxTools tools = new TmuxTools(server);
        server.sessions().get(0).newWindow("second");

        List<SessionSummary> sessions = tools.sessions();

        assertEquals(1, sessions.size());
        assertEquals("libtmux", sessions.get(0).name());
        assertTrue(
                sessions.get(0).windows().contains("second"),
                sessions.get(0).windows().toString());
    }

    @Test
    void panesAreListedWithTheIdOtherToolsTake(Server server) {
        TmuxTools tools = new TmuxTools(server);

        List<PaneSummary> panes = tools.panes();

        assertEquals(1, panes.size());
        assertTrue(panes.get(0).id().startsWith("%"), panes.get(0).id());
        assertEquals("libtmux", panes.get(0).session());
        assertTrue(panes.get(0).active());
    }

    @Test
    void aPaneRunsWhatItIsToldAndShowsIt(Server server) throws Exception {
        TmuxTools tools = new TmuxTools(server);
        String pane = tools.panes().get(0).id();

        tools.run(pane, "echo model-ran-this");

        assertTrue(await(() -> tools.capture(pane).stream().anyMatch(line -> line.contains("model-ran-this"))));
    }

    @Test
    void creatingAWindowHandsBackAPaneToUse(Server server) {
        TmuxTools tools = new TmuxTools(server);

        String pane = tools.newWindow("libtmux", "built-by-model");

        assertTrue(pane.startsWith("%"), pane);
        assertTrue(
                tools.panes().stream().anyMatch(summary -> summary.id().equals(pane)),
                "the pane handed back must be one that exists");
    }

    /** The reason tools take an id: a position a model read earlier may now mean a different pane. */
    @Test
    void aPaneIdKeepsMeaningTheSamePaneAfterItsNeighboursChange(Server server) {
        TmuxTools tools = new TmuxTools(server);
        String original = tools.panes().get(0).id();
        server.sessions().get(0).windows().get(0).split();

        List<PaneSummary> now = tools.panes();

        assertEquals(2, now.size());
        assertTrue(
                now.stream().anyMatch(summary -> summary.id().equals(original)),
                "the id a model already holds still names the pane it named before");
        assertNotEquals(now.get(0).id(), now.get(1).id());
    }

    /**
     * A model reads a listing to decide what to act on, and a server with forty panes gives it forty
     * things to reason about. Narrowing happens here, over a snapshot already taken, so it costs no
     * further commands.
     */
    @Test
    void panesCanBeNarrowedByAnExpression(Server server) {
        TmuxTools tools = new TmuxTools(server);
        server.sessions().get(0).newWindow("second");

        List<PaneSummary> active = tools.describe(
                server.panes().stream().filter(Pane_.active().isTrue()).toList());

        assertEquals(2, tools.panes().size(), "two windows, one pane each");
        assertEquals(2, active.size(), "each window's only pane is its active one");
        assertTrue(active.stream().allMatch(PaneSummary::active));
    }

    /**
     * The filter a model actually sends is JSON, and this is the whole path: schema document to
     * expression to a listing narrowed by it. A model cannot write Java, so if this path is broken
     * the typed one being correct does not help anybody.
     */
    @Test
    void aFilterArrivesAsTheVersionedJsonDocument(Server server) {
        TmuxTools tools = new TmuxTools(server);
        server.sessions().get(0).newWindow("second");
        // Wire identifiers are tmux's own format names, so this document says the same thing to
        // every port of libtmux rather than naming anything Java calls a field.
        String document = "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                + "{\"node\":\"compare\",\"field\":\"pane_active\",\"op\":\"equals\",\"value\":true}}";

        FilterExpr<Pane> parsed = FilterJson.readString(document, LibTmuxModels.pane());

        List<PaneSummary> fromJson =
                tools.describe(server.panes().stream().filter(parsed).toList());

        assertEquals(
                tools.describe(
                        server.panes().stream().filter(Pane_.active().isTrue()).toList()),
                fromJson,
                "the document and the typed expression select the same panes");
        assertEquals(2, fromJson.size());
    }

    @Test
    void aTargetThatIsNotThereSaysSoRatherThanActingOnSomethingElse(Server server) {
        TmuxTools tools = new TmuxTools(server);

        assertThrows(ObjectDoesNotExist.class, () -> tools.capture("%999"));
        assertThrows(ObjectDoesNotExist.class, () -> tools.run("%999", "echo nothing"));
        assertThrows(ObjectDoesNotExist.class, () -> tools.newWindow("no-such-session", "w"));
    }

    @Test
    void aPaneIdThatIsNotAPaneIdIsRejected(Server server) {
        TmuxTools tools = new TmuxTools(server);

        assertThrows(
                IllegalArgumentException.class,
                () -> tools.capture("1"),
                "tmux would read a bare number as something else entirely");
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
