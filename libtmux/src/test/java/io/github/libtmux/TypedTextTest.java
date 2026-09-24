package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTransport;
import io.github.libtmux.transport.TmuxTransportException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TypedTextTest {

    private static final String SEP = RowFormat.of("x").separator();

    @Test
    void anUnsubmittedLineStaysDiscountedForAWaitThatStartsWellAfterTyping() {
        long[] now = {0};
        PaneEcho echo = new PaneEcho(() -> now[0]);
        try (Server server = onePaneFixture(echo)) {
            Pane pane = server.panes().getFirst();
            pane.sendKeys(List.of("LATE-WAIT-MARKER"));

            now[0] = Duration.ofSeconds(30).toNanos();

            TypedText typed = TypedText.in(pane);
            List<String> masked = typed.withoutEcho(List.of("LATE-WAIT-MARKER"));

            assertEquals(
                    List.of(""),
                    masked,
                    "an unsubmitted line has no TTL of its own; a wait starting late must still discount it");
        }
    }

    @Test
    void anUnknownDispatchOutcomeKeepsThePossibleEchoDiscounted() {
        PaneEcho echo = new PaneEcho();
        try (Server server = Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("fixture"))
                        .build(),
                new OnePane() {
                    @Override
                    public CommandResult execute(CommandRequest request) {
                        if (request.commands().stream()
                                .flatMap(List::stream)
                                .anyMatch(arg -> arg.contains("send-keys"))) {
                            throw new TmuxTransportException("reply lost", DispatchOutcome.UNKNOWN, null);
                        }
                        return super.execute(request);
                    }
                },
                echo)) {
            Pane pane = server.panes().getFirst();
            assertThrows(TmuxTransportException.class, () -> pane.sendLiteral(List.of("possibly-delivered")));
            assertEquals(List.of(""), TypedText.in(pane).withoutEcho(List.of("possibly-delivered")));
        }
    }

    @Test
    void killingAPaneDropsItsEchoRecord() {
        PaneEcho echo = new PaneEcho();
        try (Server server = onePaneFixture(echo)) {
            Pane pane = server.panes().getFirst();
            pane.sendKeys(List.of("typed"));
            pane.kill();

            assertEquals(0, echo.size());
        }
    }

    /** One session, one window, one pane, on tmux 3.6; every other command succeeds silently. */
    static Server onePaneFixture(PaneEcho echo) {
        return Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("fixture"))
                        .build(),
                new OnePane(),
                echo);
    }

    private static class OnePane implements TmuxTransport {

        @Override
        public CommandResult execute(CommandRequest request) {
            return GroupedTmux.execute(request, 4242L, argv -> answer(argv));
        }

        private static CommandResult answer(List<String> argv) {
            return switch (argv.get(0)) {
                case "display-message" -> new CommandResult(0, List.of(row("4242", "3.6")), List.of());
                case "list-sessions" -> new CommandResult(0, List.of(row("$0", "main", "1", "1")), List.of());
                case "list-windows" ->
                    new CommandResult(
                            0, List.of(row("$0", "@1", "0", "editor", "1", "1", "1", "80", "24", "layout")), List.of());
                case "list-panes" ->
                    new CommandResult(
                            0,
                            List.of(row(
                                    "$0", "@1", "0", "%1", "1", "1", "zsh", "80", "24", "0", "0", "t", "/tmp", "11",
                                    "1", "1", "1", "1")),
                            List.of());
                default -> new CommandResult(0, List.of(), List.of());
            };
        }

        private static String row(String... fields) {
            return String.join(SEP, fields);
        }

        @Override
        public void close() {}
    }
}
