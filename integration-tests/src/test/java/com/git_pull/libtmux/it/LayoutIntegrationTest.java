package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Layout;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.UnsupportedTmuxVersion;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.junit5.TmuxExtension;
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
            if (!server.version().atLeast(new com.git_pull.libtmux.TmuxVersion(3, 5, ""))
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
        boolean hasMirrored = server.version().atLeast(new com.git_pull.libtmux.TmuxVersion(3, 5, ""));

        if (hasMirrored) {
            window.selectLayout(Layout.MAIN_VERTICAL_MIRRORED);
            assertTrue(server.isAlive());
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> window.selectLayout(Layout.MAIN_VERTICAL_MIRRORED));
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

    private static Window split(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();
        return window.refresh();
    }
}
