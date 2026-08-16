package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What a session name containing a target delimiter becomes.
 *
 * <p>tmux changes this twice inside the range this library supports, and the library passes the name
 * through, so a caller sees a different session on 3.6 than on 3.7b for the same call.
 */
@ExtendWith(TmuxExtension.class)
final class SessionNameIntegrationTest {

    private static final TmuxVersion REJECTS = new TmuxVersion(3, 7, "");
    private static final TmuxVersion ACCEPTS_AGAIN = new TmuxVersion(3, 7, "a");

    /**
     * Measured on every supported build: rewritten to {@code a_b} through 3.6, refused on 3.7, kept
     * as written from 3.7a. A caller who needs one answer has to keep delimiters out of the name.
     */
    @Test
    void aDelimiterInASessionNameIsRewrittenRefusedOrKeptDependingOnTheTmux(Server server) {
        TmuxVersion running = server.version();

        if (running.atLeast(REJECTS) && !running.atLeast(ACCEPTS_AGAIN)) {
            assertThrows(
                    LibTmuxException.class,
                    () -> server.newSession("a:b"),
                    "3.7 refuses a delimiter in a session name");
            return;
        }

        Session made = server.newSession("a:b");

        if (running.atLeast(ACCEPTS_AGAIN)) {
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
}
