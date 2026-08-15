package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.TmuxVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Which tmux the suite is actually running against.
 *
 * <p>The compatibility matrix runs this whole suite once per released tmux. That is only evidence
 * if each lane really used the build it is named after: a lane that ignored the configured binary
 * would run against whatever is on PATH and report exactly the same green.
 */
@ExtendWith(TmuxExtension.class)
final class TmuxVersionIntegrationTest {

    @Test
    void theServerReportsItsOwnVersion(Server server) {
        TmuxVersion version = server.version();

        assertTrue(version.atLeast(new TmuxVersion(3, 2, "a")), "this library supports 3.2a and later: " + version);
    }

    /**
     * The guard on the matrix. When a lane declares which tmux it is for, the running server has to
     * agree; without this the eight lanes could all be the same tmux.
     */
    @Test
    void theLaneRanTheTmuxItIsNamedAfter(Server server) {
        String expected = System.getProperty("libtmux.tmux.expected");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                expected != null, "not a matrix lane; the ordinary suite uses whichever tmux is on PATH");

        assertEquals(
                TmuxVersion.parse(expected),
                server.version(),
                "this lane ran a different tmux than the one it claims to cover");
    }
}
