package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.Window;
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
     * The consequence of 3.7a accepting the delimiter: a target splits on {@code :}, so the name can
     * no longer address the session and the id is the only handle that works.
     */
    @Test
    void aNameKeptWithItsDelimiterIsNoLongerAddressableByName(Server server) {
        if (!server.version().atLeast(ACCEPTS_AGAIN)) {
            return;
        }
        Session made = server.newSession("a:b");

        assertEquals("a:b", made.name());
        assertEquals(made.id(), made.refresh().id(), "the id still addresses it");
        assertThrows(
                LibTmuxException.class,
                () -> server.killSession("a:b"),
                "tmux reads the delimiter as a window, so the name cannot select the session");
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
