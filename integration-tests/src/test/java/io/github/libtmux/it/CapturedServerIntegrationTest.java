package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.CapturedServer;
import io.github.libtmux.Server;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TmuxExtension.class)
final class CapturedServerIntegrationTest {

    @Test
    void capturedHandlesRetainLinkedPlacementsAfterLiveChanges(Server server) {
        assertTrue(server.cmd("new-session", "-d", "-s", "other").succeeded());
        assertTrue(server.cmd("link-window", "-s", "libtmux:0", "-t", "other:9").succeeded());
        AtomicInteger commands = new AtomicInteger();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport counting = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    commands.incrementAndGet();
                    return processes.execute(request);
                }

                @Override
                public void close() {}
            };
            try (Server measured = Server.using(server.config(), counting)) {
                CapturedServer captured = measured.capture();
                assertEquals(2, commands.get());
                Window first = captured.sessions().stream()
                        .filter(session -> session.name().equals("libtmux"))
                        .findFirst()
                        .orElseThrow()
                        .windows()
                        .getFirst();
                List<Window> links = captured.windows().stream()
                        .filter(window -> window.id().equals(first.id()))
                        .toList();
                assertEquals(2, links.size());
                assertNotEquals(links.getFirst().context(), links.getLast().context());
                assertEquals(
                        links.getFirst().panes().getFirst().id(),
                        links.getLast().panes().getFirst().id());
                assertTrue(server.cmd("rename-window", "-t", "libtmux:0", "changed-after-capture")
                        .succeeded());
                assertEquals(first.name(), links.getLast().name());
                assertNotEquals("changed-after-capture", first.name());
                assertEquals(
                        captured.snapshot().serverPid(), captured.identity().processId());
                assertEquals(
                        captured.snapshot().clients().size(), captured.clients().size());
                captured.clients().forEach(client -> client.attachment());
                captured.panes().forEach(pane -> pane.window().session().windows());
                assertEquals(2, commands.get(), "captured clients and relations issue no further commands");
                assertEquals(
                        "changed-after-capture",
                        server.window(first.context()).orElseThrow().name());
            }
        }
    }
}
