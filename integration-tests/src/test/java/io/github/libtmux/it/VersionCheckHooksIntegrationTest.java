package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The version probe must have no effects a user's hooks can see.
 *
 * <p>{@code display-message} fires {@code after-display-message} on every release, so a probe made
 * with it ran a user's hook before a refusal could stop anything. Each test arms a hook that records
 * that it fired, runs ordinary checked calls, and requires the record to stay empty; a control then
 * fires the same hook the ordinary way, so an empty record means the hook was quiet and not that it
 * was never armed.
 */
@ExtendWith(TmuxExtension.class)
final class VersionCheckHooksIntegrationTest {

    private static final TmuxVersion COMMAND_ERROR_HOOK = new TmuxVersion(3, 5, "");

    @Test
    void versionCheckFiresNoAfterDisplayMessageHook(Server server) {
        assertTrue(server.cmd("set-hook", "-g", "after-display-message", "set-option -g @probe-hook fired")
                .succeeded());

        for (int call = 0; call < 3; call++) {
            assertTrue(server.cmd("list-sessions").succeeded());
        }
        server.requireCompatibleTmux();

        assertEquals("", recorded(server), "the version check ran the user's after-display-message hook");

        assertTrue(server.cmd("display-message", "-p", "control").succeeded());
        assertEquals("fired", recorded(server), "the hook never fires, so the check above proves nothing");
    }

    @Test
    void versionCheckFiresNoCommandErrorHook(Server server) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                server.version().atLeast(COMMAND_ERROR_HOOK), "command-error is a hook from tmux 3.5");
        assertTrue(server.cmd("set-hook", "-g", "command-error", "set-option -g @probe-hook fired")
                .succeeded());

        for (int call = 0; call < 3; call++) {
            assertTrue(server.cmd("list-sessions").succeeded());
        }
        server.requireCompatibleTmux();

        assertEquals("", recorded(server), "the version check ran the user's command-error hook");

        assertFalse(server.cmd("kill-session", "-t", "=libtmux-no-such-session").succeeded());
        assertEquals("fired", recorded(server), "the hook never fires, so the check above proves nothing");
    }

    private static String recorded(Server server) {
        // -q: an option never set is an empty answer, not an error, and raw cmd adds no hook itself.
        return String.join(
                "\n", server.cmd("show-options", "-gqv", "@probe-hook").stdout());
    }
}
