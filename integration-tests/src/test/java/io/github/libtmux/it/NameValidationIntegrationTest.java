package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.Window;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.junit5.TmuxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What a name containing a target delimiter becomes. tmux changes its mind inside the supported
 * range and this library passes the name through, so the same call answers differently per release.
 */
@ExtendWith(TmuxExtension.class)
final class NameValidationIntegrationTest {

    private static final TmuxVersion REJECTS = new TmuxVersion(3, 7, "");
    private static final TmuxVersion ACCEPTS_AGAIN = new TmuxVersion(3, 7, "a");

    private static boolean refuses(Server server) {
        return server.version().atLeast(REJECTS) && !server.version().atLeast(ACCEPTS_AGAIN);
    }

    /**
     * Measured on every supported build: rewritten to {@code a_b} through 3.6, refused on 3.7, kept
     * as written from 3.7a. A caller who needs one answer has to keep delimiters out of the name.
     */
    @Test
    void aDelimiterInASessionNameIsRewrittenRefusedOrKeptDependingOnTheTmux(Server server) {
        if (refuses(server)) {
            assertThrows(LibTmuxException.class, () -> server.newSession("a:b"));
            return;
        }

        Session made = server.newSession("a:b");

        if (server.version().atLeast(ACCEPTS_AGAIN)) {
            assertEquals("a:b", made.name());
        } else {
            assertEquals("a_b", made.name());
            assertTrue(server.hasSession("a_b"), "a rewritten name is one tmux still answers to");
        }
    }

    /**
     * 3.7a accepts the delimiter, and a {@code -t} target would split on it. The library resolves
     * the name from a listing instead, so the name still addresses the session.
     */
    @Test
    void aNameKeptWithItsDelimiterIsStillAddressableByName(Server server) {
        if (!server.version().atLeast(ACCEPTS_AGAIN)) {
            return;
        }
        Session made = server.newSession("a:b");

        assertEquals("a:b", made.name());
        assertEquals(made.id(), made.refresh().id(), "the id still addresses it");
        assertTrue(server.hasSession("a:b"), "the name is real; only a -t built from it is unusable");
        assertEquals(made.id(), server.session("a:b").orElseThrow().id());
        server.killSession("a:b");
        assertFalse(server.hasSession("a:b"));
    }

    /**
     * A lookup finds the name tmux stored for the one it was given, and nothing else: from 3.7 a
     * dot is not an underscore, so {@code x.y} must not answer with a session called {@code x_y}.
     */
    @Test
    void aNameIsNotMistakenForAnotherSessionsStoredName(Server server) {
        Session underscored = server.newSession("x_y");

        if (server.version().atLeast(REJECTS)) {
            assertFalse(server.hasSession("x.y"));
            assertTrue(server.session("x.y").isEmpty());
            assertThrows(LibTmuxException.class, () -> server.killSession("x.y"));
            assertTrue(server.hasSession("x_y"), "a refused kill still ended " + underscored.name());
        } else {
            // Before 3.7 tmux stores x.y as x_y itself, so they name the same session.
            assertEquals(underscored.id(), server.session("x.y").orElseThrow().id());
        }
    }

    /** Every release doubles a backslash; the given name and the reported one both find it. */
    @Test
    void aBackslashNameIsFoundByWhatWasGivenAndByWhatTmuxReports(Server server) {
        Session made = server.newSession("b\\c\\");

        assertEquals("b\\\\c\\\\", made.name());
        assertEquals(made.id(), server.session("b\\c\\").orElseThrow().id());
        assertEquals(made.id(), server.session(made.name()).orElseThrow().id());
        assertTrue(server.hasSession("b\\c\\"));
        server.killSession(made.name());
        assertFalse(server.hasSession("b\\c\\"));
    }

    /**
     * Window names take a different path from session names: kept as written on every supported
     * build except 3.7, which refuses them. Measured rather than derived from the session rule.
     */
    @Test
    void aDelimiterInAWindowNameIsKeptEverywhereExceptTheOneReleaseThatRefusesIt(Server server) {
        Window window = server.sessions().get(0).windows().get(0);

        if (refuses(server)) {
            assertThrows(LibTmuxException.class, () -> window.rename("w:x"));
            return;
        }

        assertEquals("w:x", window.rename("w:x").name(), "a window name is never rewritten");
    }
}
