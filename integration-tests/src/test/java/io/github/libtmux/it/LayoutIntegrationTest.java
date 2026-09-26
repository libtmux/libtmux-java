package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.Layout;
import io.github.libtmux.Layouts;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.Window;
import io.github.libtmux.WindowLayout;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Rearranging a window's panes, and refusing to hand tmux something that would end it.
 *
 * <p>tmux 3.3a does not survive a layout it cannot parse: {@code select-layout} with an unknown name
 * or a malformed layout string ends the server and every session on the socket, including ones this
 * program never created. Both ways in are closed before tmux is asked — the names by an enum, the
 * strings by tmux's own checksum — so the case that would prove the danger cannot be written here.
 */
@ExtendWith(TmuxExtension.class)
final class LayoutIntegrationTest {

    @Test
    void everyLayoutTheReleaseHasIsAcceptedAndTheServerSurvives(Server server) {
        Window window = split(server);

        for (Layout layout : Layout.values()) {
            if (!server.version().atLeast(new TmuxVersion(3, 5, ""))
                    && layout.tmuxName().contains("mirrored")) {
                continue;
            }
            window.selectLayout(layout);

            assertTrue(server.isAlive(), "the server did not survive " + layout);
        }
    }

    @Test
    void aLayoutThisReleaseDoesNotHaveIsRefusedRatherThanSent(Server server) {
        Window window = split(server);
        boolean hasMirrored = server.version().atLeast(new TmuxVersion(3, 5, ""));

        if (hasMirrored) {
            window.selectLayout(Layout.MAIN_VERTICAL_MIRRORED);
            assertTrue(server.isAlive());
        } else {
            assertThrows(UnsupportedFeatureException.class, () -> window.selectLayout(Layout.MAIN_VERTICAL_MIRRORED));
            assertTrue(server.isAlive(), "a refusal must not have reached tmux");
        }
    }

    @Test
    void choosingALayoutChangesTheArrangement(Server server) {
        Window window = split(server);
        window.selectLayout(Layout.EVEN_HORIZONTAL);
        WindowLayout horizontal = window.refresh().layout();

        window.selectLayout(Layout.EVEN_VERTICAL);

        assertNotEquals(horizontal, window.refresh().layout(), "the layout string did not change");
    }

    @Test
    void movingToTheNextLayoutIsAcceptedOnEveryRelease(Server server) {
        Window window = split(server);
        WindowLayout before = window.refresh().layout();

        window.nextLayout();

        assertTrue(server.isAlive());
        assertNotEquals(before, window.refresh().layout(), "next-layout did nothing");
    }

    /**
     * {@code previous-layout} steps back through tmux's preset cycle. The window starts on a preset
     * because a fresh split is not a position in that cycle, so stepping back from one step forward
     * would not return to it.
     */
    @Test
    void movingToThePreviousLayoutIsAcceptedOnEveryRelease(Server server) {
        Window window = split(server);
        window.selectLayout(Layout.EVEN_HORIZONTAL);
        WindowLayout before = window.refresh().layout();
        window.nextLayout();

        window.previousLayout();

        assertTrue(server.isAlive());
        assertEquals(before, window.refresh().layout(), "previous-layout did not undo next-layout");
    }

    // ----------------------------------------------------------------------- the dangerous path

    /** The form a window reports is the one its server writes: JSON from tmux 3.8, classic before. */
    @Test
    void aLayoutSaysWhichFormThisServerWrote(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();

        WindowLayout layout = window.refresh().layout();

        if (server.version().atLeast(new TmuxVersion(3, 8, ""))) {
            assertInstanceOf(WindowLayout.Json.class, layout, "tmux 3.8 and later write JSON: " + layout);
        } else {
            assertInstanceOf(
                    WindowLayout.Classic.class, layout, "releases before 3.8 write the classic form: " + layout);
        }
    }

    /** A layout tmux wrote round-trips, which is what applyLayout is for. */
    @Test
    void anExactArrangementCanBeReadBackAndRestored(Server server) {
        Window window = split(server);
        window.selectLayout(Layout.EVEN_HORIZONTAL);
        WindowLayout wanted = window.refresh().layout();
        window.selectLayout(Layout.EVEN_VERTICAL);

        window.applyLayout(wanted);

        assertEquals(wanted, window.refresh().layout(), "the arrangement did not come back");
        assertTrue(server.isAlive());
    }

    @Test
    void workspacePreflightAcceptsCapturedJsonAndRefusesTooFewCells(Server server) {
        String leaf = "{\"V\":2,\"L\":{\"t\":\"p\",\"w\":80,\"h\":24,\"x\":0,\"y\":0,\"i\":0}}";
        if (!server.version().atLeast(new TmuxVersion(3, 8, ""))) {
            assertThrows(UnsupportedFeatureException.class, () -> Layouts.require(leaf, server, 1));
            return;
        }
        Window window = split(server);
        String captured = window.refresh().layout().value();
        var panes = window.refresh().panes().stream()
                .map(io.github.libtmux.Pane::id)
                .toList();
        assertEquals(captured, Layouts.require(captured, server, 2));
        window.selectLayout(Layout.TILED);
        window.applyLayout(captured);
        assertEquals(captured, window.refresh().layout().value());
        assertThrows(IllegalArgumentException.class, () -> Layouts.require(leaf, server, 2));
        for (String invalid :
                java.util.List.of(leaf.replace("\"V\":2", "\"V\":2e0"), leaf.replace("\"V\":2", "\"V\":2,\"V\":2"))) {
            assertThrows(IllegalArgumentException.class, () -> Layouts.require(invalid, server, 1));
            assertTrue(!server.cmd("select-layout", "-t", window.id().value(), invalid)
                    .succeeded());
        }
        assertEquals(
                panes,
                window.refresh().panes().stream()
                        .map(io.github.libtmux.Pane::id)
                        .toList());
    }

    @Test
    void javaValidationRefusesGeometryThatTmuxWouldRepair(Server server) {
        Window window = server.windows().getFirst();
        for (String layout : java.util.List.of(
                "8a08,1x1,0,0{39x24,0,0,0,40x24,40,0,1}", "79f5,80x24,0,0{39x23,0,0,0,40x24,40,0,1}")) {
            assertThrows(IllegalArgumentException.class, () -> Layouts.require(layout, server, 1));
            assertTrue(server.cmd("select-layout", "-t", window.id().value(), layout)
                    .succeeded());
        }
        assertEquals(1, window.refresh().panes().size());
    }

    @Test
    void savedLayoutInputCanExceedTheDumpBuffer(Server server) {
        Window window = server.windows().getFirst();
        var keeper = server.newSession("keeper");
        long pid = server.snapshot().serverPid().orElseThrow();
        var keeperLayout = keeper.windows().getFirst().layout();
        var body = new StringJoiner(",", "1199x24,0,0{", "}");
        for (int pane = 0; pane < 600; pane++) body.add("1x24," + (2 * pane) + ",0," + pane);
        int checksum = 0;
        for (char value : body.toString().toCharArray())
            checksum = ((checksum >> 1) + ((checksum & 1) << 15) + value) & 0xffff;
        String layout = "%04x,%s".formatted(checksum, body);
        assertTrue(body.length() > 8192, "the input must exceed tmux's layout_dump buffer");

        window.applyLayout(layout);

        assertEquals(pid, server.snapshot().serverPid().orElseThrow());
        assertEquals(2, server.sessions().size());
        assertEquals(1, window.refresh().panes().size(), "tmux prunes the extra cells");
        assertEquals(1199, window.refresh().panes().getFirst().size().width());
        assertEquals(keeperLayout, keeper.refresh().windows().getFirst().layout());
    }

    /**
     * The check that matters. On 3.3a this string would end the server; everywhere else it would be
     * an ordinary error. It never reaches tmux on any release, so the server is still standing after.
     */
    @Test
    void aLayoutTmuxDidNotWriteIsRefusedBeforeTmuxSeesIt(Server server) {
        Window window = split(server);

        assertThrows(IllegalArgumentException.class, () -> window.applyLayout("zzzz,999x999,0,0,9"));
        assertThrows(IllegalArgumentException.class, () -> window.applyLayout("not-a-layout"));
        assertThrows(IllegalArgumentException.class, () -> window.applyLayout(""));
        assertThrows(IllegalArgumentException.class, () -> window.applyLayout("abcd"));
        assertThrows(
                IllegalArgumentException.class,
                () -> window.applyLayout("-x"),
                "a leading dash is a flag to select-layout, not a layout");

        assertTrue(server.isAlive(), "a refused layout must never have reached tmux");
        assertEquals(1, server.sessions().size(), "and no session was lost");
    }

    /** A checksum that does not match its body is exactly the shape that crashes 3.3a. */
    @Test
    void aLayoutWithTheWrongChecksumIsRefused(Server server) {
        Window window = split(server);
        String real = window.refresh().layout().value();
        String corrupted = "0000" + real.substring(4);

        assertThrows(IllegalArgumentException.class, () -> window.applyLayout(corrupted));

        assertTrue(server.isAlive());
    }

    /**
     * JSON carries no checksum, so the guard can only ask whether this tmux could have written it.
     * Below 3.8 the answer is always no, whatever the body says - refused before tmux sees it.
     */
    @Test
    void aJsonShapedLayoutIsRefusedBeforeItsFloor(Server server) {
        assumeTmuxOlderThanJsonLayouts(server);
        Window window = split(server);

        assertThrows(UnsupportedFeatureException.class, () -> window.applyLayout("{}"));

        assertTrue(server.isAlive(), "a refusal must not have reached tmux");
    }

    /**
     * From 3.8, a JSON body that merely has the shape is tmux's own problem to refuse - and it does,
     * with an ordinary error rather than the 3.3a crash. This is what proves the shape check does not
     * need to parse the body: tmux itself is safe against one that only looks right.
     */
    @Test
    void aJsonShapedLayoutThatIsNotRealIsRefusedByTmuxItself(Server server) {
        assumeTmuxAtLeastJsonLayouts(server);
        Window window = split(server);

        assertThrows(LibTmuxException.class, () -> window.applyLayout("{}"));

        assertTrue(server.isAlive(), "tmux must reject a fake JSON layout without dying");
    }

    private static final TmuxVersion JSON_LAYOUT_FLOOR = new TmuxVersion(3, 8, "");

    private static void assumeTmuxOlderThanJsonLayouts(Server server) {
        assumeFalse(server.version().atLeast(JSON_LAYOUT_FLOOR), "needs a tmux older than JSON layouts");
    }

    private static void assumeTmuxAtLeastJsonLayouts(Server server) {
        assumeTrue(server.version().atLeast(JSON_LAYOUT_FLOOR), "needs a tmux with JSON layouts");
    }

    private static Window split(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();
        return window.refresh();
    }
}
