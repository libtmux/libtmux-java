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
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> client.send(
                                "break-pane", "-n", "bad", "-s", pane.id().value()));
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> server.cmd("breakp", "-n", "bad", "-s", pane.id().value()));
                assertTrue(client.isAlive());
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
