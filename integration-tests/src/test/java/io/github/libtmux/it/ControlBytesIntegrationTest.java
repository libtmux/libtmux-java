package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutputBytes;
import io.github.libtmux.control.PaneOutputDecoder;
import io.github.libtmux.junit5.TmuxExtension;
import java.io.ByteArrayOutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TmuxExtension.class)
final class ControlBytesIntegrationTest {
    @Test
    void splitUtf8AndInvalidBytesSurviveTheControlTransport(Server server) throws Exception {
        try (var client = ControlClient.attach(
                        server.config(), server.sessions().getFirst().id());
                var events = client.subscribeOutputBytes(32, 4096)) {
            var reply = client.send(
                    "new-window",
                    "-P",
                    "-F",
                    "#{pane_id}",
                    "stty -echo; printf 'READY'; read -r start; printf '\\303'; read -r more; printf '\\251\\377'; read -r finish");
            assertTrue(reply.succeeded());
            PaneId pane = new PaneId(reply.lines().getFirst());
            assertArrayEquals("READY".getBytes(StandardCharsets.US_ASCII), take(events, pane, 5));
            var decoder = new PaneOutputDecoder(CodingErrorAction.REPLACE);
            client.send("send-keys", "-t", pane.value(), "Enter");
            byte[] first = take(events, pane, 1);
            assertArrayEquals(new byte[] {(byte) 0xc3}, first);
            assertEquals("", decoder.decode(new PaneOutputBytes(pane, first), 0).data());
            client.send("send-keys", "-t", pane.value(), "Enter");
            byte[] second = take(events, pane, 2);
            assertArrayEquals(new byte[] {(byte) 0xa9, (byte) 0xff}, second);
            assertEquals(
                    "é�", decoder.decode(new PaneOutputBytes(pane, second), 0).data());
            assertEquals(0, events.droppedCount());
        }
    }

    @Test
    void detachingAControlClientDoesNotClaimTheServerDied(Server server) throws Exception {
        try (var client = ControlClient.attach(
                        server.config(), server.sessions().getFirst().id());
                var events = client.subscribeOutputBytes(4, 1024)) {
            String name = client.send("display-message", "-p", "#{client_name}")
                    .lines()
                    .getFirst();
            assertTrue(server.cmd("detach-client", "-t", name).succeeded());
            while (events.next(Duration.ofSeconds(1)).isPresent()) {}
            assertEquals(
                    EventSubscription.EndReason.CONTROL_EXIT,
                    events.termination().orElseThrow().reason());
            assertTrue(server.cmd("display-message", "-p", "still-alive").succeeded());
        }
    }

    private static byte[] take(EventSubscription<PaneOutputBytes> events, PaneId pane, int count) throws Exception {
        var bytes = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (bytes.size() < count) {
            var next = events.next(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))
                    .orElseThrow();
            if (next.pane().equals(pane)) {
                bytes.write(next.data());
            }
        }
        return bytes.toByteArray();
    }
}
