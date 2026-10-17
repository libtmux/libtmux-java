package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.ControlAcquisition;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Control has its own floor; below it every lane asserts the refusal, above it the attach works. */
@ExtendWith(TmuxExtension.class)
final class ControlFloorIntegrationTest {
    private static final TmuxVersion CONTROL = new TmuxVersion(3, 7, "");

    @Test
    void controlRequiresItsOwnFloorAndLiteralBreakRequiresRepair(Server server) {
        Pane pane = server.panes().getFirst();
        TmuxVersion running = server.version();
        if (!ControlAcquisition.supports(running)) {
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> server.control(server.sessions().getFirst()));
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> ControlClient.attachUnfenced(
                            server.config(), server.sessions().getFirst().id()));
            assertTrue(server.clients().isEmpty());
            return;
        }
        try (ControlClient client = server.control(server.sessions().getFirst())) {
            assertTrue(client.isAlive());
            if (running.equals(CONTROL)) {
                // Every spelling tmux resolves to break-pane: the name, its alias, and each unique
                // prefix down to "br", on every path a command can take.
                for (String verb : List.of("break-pane", "breakp", "break-p", "break-pa", "break", "brea", "br")) {
                    assertThrows(
                            UnsupportedFeatureException.class,
                            () -> client.send(verb, "-n", "bad", "-s", pane.id().value()),
                            verb + " over control");
                    assertThrows(
                            UnsupportedFeatureException.class,
                            () -> server.cmd(verb, "-n", "bad", "-s", pane.id().value()),
                            verb + " raw");
                    assertThrows(
                            UnsupportedFeatureException.class,
                            () -> server.batch()
                                    .add(verb, "-d", "-s", pane.id().value())
                                    .run(),
                            verb + " in a batch");
                    assertThrows(
                            UnsupportedFeatureException.class,
                            () -> server.chain()
                                    .then("list-sessions")
                                    .then(verb, "-d", "-s", pane.id().value())
                                    .run(),
                            verb + " in a chain");
                    assertThrows(
                            UnsupportedFeatureException.class,
                            () -> server.cmd(verb, "-d", "-s", pane.id().value()),
                            "nameless " + verb);
                }
                assertTrue(client.isAlive());
                assertTrue(server.isAlive());
            }
        }
        try (ControlClient client = ControlClient.attachUnfenced(
                server.config(), server.sessions().getFirst().id())) {
            assertEquals(
                    List.of("roundtrip"),
                    client.send("display-message", "-p", "roundtrip").lines());
        }
        assertTrue(server.isAlive());
    }
}
