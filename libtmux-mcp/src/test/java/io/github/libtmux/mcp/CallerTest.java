package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TmuxExtension.class)
final class CallerTest {

    @Test
    void onlyTwoAbsentVariablesMeanDetached(Server server) {
        assertFalse(Caller.of(server, Map.of()).uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX", "")).uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX_PANE", "%0")).uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX", validTmux(server), "TMUX_PANE", ""))
                .uncertain());
    }

    @Test
    void selectedSocketWithWrongPidIsUncertain(Server server) {
        String socket = server.expand("#{socket_path}");
        long pid = Long.parseLong(server.expand("#{pid}"));
        String session = server.sessions().getFirst().id().value().substring(1);

        Caller caller = Caller.of(server, Map.of("TMUX", socket + "," + (pid + 1) + "," + session, "TMUX_PANE", "%0"));

        assertTrue(caller.uncertain());
    }

    @Test
    void canonicalForeignSocketIsNotSelected(Server server) {
        long pid = Long.parseLong(server.expand("#{pid}"));
        String session = server.sessions().getFirst().id().value().substring(1);

        Caller caller = Caller.of(server, Map.of("TMUX", "/dev/null," + pid + "," + session, "TMUX_PANE", "%0"));

        assertFalse(caller.uncertain());
        assertTrue(caller.pane().isEmpty());
    }

    @Test
    void noncanonicalSelectedClaimsAreUncertain(Server server) {
        String socket = server.expand("#{socket_path}");
        String pid = server.expand("#{pid}");
        String session = server.sessions().getFirst().id().value().substring(1);

        assertTrue(Caller.of(server, Map.of("TMUX", socket + ",0" + pid + "," + session, "TMUX_PANE", "%0"))
                .uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX", socket + "," + pid + ",0" + session, "TMUX_PANE", "%0"))
                .uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX", socket + "," + pid + ",$" + session, "TMUX_PANE", "%0"))
                .uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX", socket + "," + pid + "," + session, "TMUX_PANE", "%00"))
                .uncertain());
        assertTrue(Caller.of(server, Map.of("TMUX", "relative," + pid + "," + session, "TMUX_PANE", "%0"))
                .uncertain());
    }

    private static String validTmux(Server server) {
        return server.expand("#{socket_path}") + "," + server.expand("#{pid}") + ","
                + server.sessions().getFirst().id().value().substring(1);
    }
}
