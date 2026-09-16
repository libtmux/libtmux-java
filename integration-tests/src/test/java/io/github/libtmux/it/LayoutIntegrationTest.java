package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.libtmux.Layout;
import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.UnsupportedTmuxVersionException;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
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
            assertThrows(
                    UnsupportedTmuxVersionException.class, () -> window.selectLayout(Layout.MAIN_VERTICAL_MIRRORED));
            assertTrue(server.isAlive(), "a refusal must not have reached tmux");
        }
    }

    @Test
    void choosingALayoutChangesTheArrangement(Server server) {
        Window window = split(server);
        window.selectLayout(Layout.EVEN_HORIZONTAL);
        String horizontal = window.refresh().layout();

        window.selectLayout(Layout.EVEN_VERTICAL);

        assertNotEquals(horizontal, window.refresh().layout(), "the layout string did not change");
    }

    @Test
    void movingToTheNextLayoutIsAcceptedOnEveryRelease(Server server) {
        Window window = split(server);
        String before = window.refresh().layout();

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
        String before = window.refresh().layout();
        window.nextLayout();

        window.previousLayout();

        assertTrue(server.isAlive());
        assertEquals(before, window.refresh().layout(), "previous-layout did not undo next-layout");
    }

    // ----------------------------------------------------------------------- the dangerous path

    /** A layout tmux wrote round-trips, which is what applyLayout is for. */
    @Test
    void anExactArrangementCanBeReadBackAndRestored(Server server) {
        Window window = split(server);
        window.selectLayout(Layout.EVEN_HORIZONTAL);
        String wanted = window.refresh().layout();
        window.selectLayout(Layout.EVEN_VERTICAL);

        window.applyLayout(wanted);

        assertEquals(wanted, window.refresh().layout(), "the arrangement did not come back");
        assertTrue(server.isAlive());
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
        String real = window.refresh().layout();
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

        assertThrows(UnsupportedTmuxVersionException.class, () -> window.applyLayout("{}"));

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
