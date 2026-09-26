package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Whether a failed request may be resent is read off the commands, so a change never passes as a read. */
final class IdempotenceTest {

    record Case(List<List<String>> commands, Idempotence expected) {}

    static List<Case> cases() {
        String guard = "#{==:#{pid},4242}";
        return List.of(
                new Case(List.of(List.of("list-sessions", "-F", "#{session_id}")), Idempotence.IDEMPOTENT),
                new Case(List.of(List.of("lsp", "-a")), Idempotence.IDEMPOTENT),
                new Case(List.of(List.of("display-message", "-p", "#{pid}")), Idempotence.IDEMPOTENT),
                new Case(List.of(List.of("display-message", "-t", "%1", "-p", "x")), Idempotence.NOT_IDEMPOTENT),
                new Case(List.of(List.of("display-message", "hello")), Idempotence.NOT_IDEMPOTENT),
                new Case(List.of(List.of("capture-pane", "-p", "-t", "%1")), Idempotence.IDEMPOTENT),
                new Case(List.of(List.of("capture-pane", "-ep", "-t", "%1")), Idempotence.IDEMPOTENT),
                new Case(List.of(List.of("capture-pane", "-b", "saved", "-t", "%1")), Idempotence.NOT_IDEMPOTENT),
                new Case(List.of(List.of("capture-pane", "-pb", "saved")), Idempotence.NOT_IDEMPOTENT),
                new Case(List.of(List.of("send-keys", "-t", "%1", "ls")), Idempotence.NOT_IDEMPOTENT),
                new Case(List.of(List.of("run-shell", "true")), Idempotence.NOT_IDEMPOTENT),
                new Case(
                        List.of(List.of("if-shell", "-F", guard, "capture-pane -p -t %1", "libtmux-stale-handle-4242")),
                        Idempotence.IDEMPOTENT),
                new Case(
                        List.of(List.of("if-shell", "-F", guard, "send-keys -t %1 ls", "libtmux-stale-handle-4242")),
                        Idempotence.NOT_IDEMPOTENT),
                new Case(
                        List.of(List.of(
                                "if-shell", "-F", "-t", "s:1", guard, "list-panes", "libtmux-stale-winlink-4242-@1")),
                        Idempotence.IDEMPOTENT),
                new Case(
                        List.of(List.of(
                                "if-shell", "-F", guard, "list-panes ; kill-server", "libtmux-stale-handle-4242")),
                        Idempotence.NOT_IDEMPOTENT),
                new Case(List.of(List.of("list-sessions"), List.of("list-windows", "-a")), Idempotence.IDEMPOTENT),
                new Case(
                        List.of(List.of("list-sessions"), List.of("kill-session", "-t", "a")),
                        Idempotence.NOT_IDEMPOTENT));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aRequestIsIdempotentOnlyWhenEveryCommandReads(Case c) {
        CommandRequest request = new CommandRequest(List.of("tmux"), c.commands(), Duration.ofSeconds(1), "");

        assertEquals(c.expected(), request.idempotence(), c.commands().toString());
    }
}
