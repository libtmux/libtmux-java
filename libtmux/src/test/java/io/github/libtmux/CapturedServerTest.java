package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CapturedServerTest {

    @Test
    void everyHandleAndRelationRetainsTheSameCaptureWithoutCommands() {
        AtomicInteger commands = new AtomicInteger();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                commands.incrementAndGet();
                return GroupedTmux.execute(
                        request,
                        4242L,
                        argv -> new CommandResult(
                                0,
                                switch (argv.getFirst()) {
                                    case "display-message" -> List.of(row("4242", "3.6"));
                                    case "list-sessions" ->
                                        List.of(row("$0", "first", "1", "1"), row("$1", "second", "0", "1"));
                                    case "list-windows" ->
                                        List.of(
                                                row("$0", "@0", "0", "shared", "1", "1", "1", "80", "24", "layout"),
                                                row("$1", "@0", "9", "shared", "1", "1", "1", "80", "24", "layout"));
                                    case "list-panes" -> List.of(pane("$0", "0"), pane("$1", "9"));
                                    case "list-clients" -> List.of(row("client", "$0"));
                                    default -> throw new AssertionError(argv);
                                },
                                List.of()));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), transport)) {
            CapturedServer captured = server.capture();
            var snapshot = captured.snapshot();
            assertEquals(2, commands.get());
            assertSame(server, captured.server());
            assertEquals(snapshot.serverPid(), captured.identity().processId());
            assertEquals(2, captured.sessions().size());
            assertEquals(2, captured.windows().size());
            assertEquals(2, captured.panes().size());
            assertEquals(1, captured.clients().size());
            captured.sessions().forEach(session -> {
                assertSame(snapshot, session.snapshot());
                assertSame(snapshot, session.activeWindow().orElseThrow().snapshot());
                session.windows().forEach(window -> assertSame(snapshot, window.snapshot()));
            });
            captured.windows().forEach(window -> {
                assertSame(snapshot, window.snapshot());
                assertSame(snapshot, window.session().snapshot());
                assertSame(snapshot, window.activePane().orElseThrow().snapshot());
                window.panes().forEach(pane -> assertSame(snapshot, pane.snapshot()));
            });
            captured.panes().forEach(pane -> {
                assertSame(snapshot, pane.snapshot());
                assertSame(snapshot, pane.window().snapshot());
            });
            captured.clients().forEach(client -> {
                assertSame(snapshot, client.snapshot());
                assertSame(snapshot, client.session().orElseThrow().snapshot());
                ClientAttachment attachment = client.attachment().orElseThrow();
                assertSame(snapshot, attachment.session().snapshot());
                assertSame(snapshot, attachment.activeWindow().snapshot());
                assertSame(snapshot, attachment.activePane().snapshot());
            });
            assertEquals(
                    List.of("$0", "$1"),
                    captured.sessions().stream().map(s -> s.id().value()).toList());
            assertEquals(
                    captured.panes().getFirst().id(), captured.panes().getLast().id());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> captured.clients().clear());
            assertEquals(2, commands.get(), "metadata, clients, and all captured relations must remain pure");
        }
    }

    private static String pane(String session, String index) {
        return row(
                session, "@0", index, "%0", "0", "1", "sh", "80", "24", "0", "0", "title", "/", "42", "1", "1", "1",
                "1");
    }

    private static String row(String... fields) {
        return String.join(RowFormat.of("field").separator(), fields);
    }
}
