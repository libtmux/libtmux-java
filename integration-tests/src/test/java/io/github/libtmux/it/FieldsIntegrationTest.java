package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.junit5.FakeTmux;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Fields a capture does not carry, read for many panes or many names at once. */
@ExtendWith(TmuxExtension.class)
final class FieldsIntegrationTest {

    @Test
    void chosenFieldsArriveForEveryPane(Server server) {
        server.sessions().get(0).windows().get(0).split();

        Map<PaneId, Map<String, String>> fields = server.paneFields(List.of("pane_tty", "pane_dead"));

        assertEquals(server.panes().stream().map(Pane::id).toList(), List.copyOf(fields.keySet()));
        fields.values().forEach(pane -> {
            assertTrue(pane.getOrDefault("pane_tty", "").startsWith("/dev/"), "a tty: " + pane);
            assertEquals("0", pane.get("pane_dead"));
        });
    }

    @Test
    void variablesStillAnswerByName(Server server) {
        Map<String, String> read = server.variables(List.of("pid", "session_name"));

        assertEquals(List.of("pid", "session_name"), List.copyOf(read.keySet()));
        assertEquals(server.sessions().get(0).name(), read.get("session_name"));
    }

    @Test
    void aNameThatIsNotATmuxVariableIsRefusedBeforeItIsSent(Server server) {
        assertThrows(IllegalArgumentException.class, () -> server.paneFields(List.of("pane_tty}#{pid")));
        assertThrows(IllegalArgumentException.class, () -> server.variables(List.of("#(date)")));
    }

    /**
     * The defect this pins: reading eight names cost eight tmux processes, each at its own moment.
     * Counted against the fake, which records every command it was sent.
     */
    @Test
    void eachReadIsOneCommandHoweverManyNames() {
        FakeTmux tmux = new FakeTmux();
        tmux.addSession("work");
        try (Server server = tmux.server()) {
            int before = tmux.sent().size();
            server.variables(List.of("pid", "version", "session_name", "window_name"));
            assertEquals(1, tmux.sent().size() - before, "four names, one command: " + tmux.sent());

            before = tmux.sent().size();
            server.paneFields(List.of("pane_tty", "pane_pid", "pane_current_command"));
            assertEquals(1, tmux.sent().size() - before, "every pane, one command");
        }
    }
}
