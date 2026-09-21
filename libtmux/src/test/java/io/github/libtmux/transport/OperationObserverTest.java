package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class OperationObserverTest {

    @Test
    void aFinishedCommandReportsItsVerbAndNotItsArgument() throws Exception {
        List<OperationReport> seen = new CopyOnWriteArrayList<>();
        try (ProcessTransport transport =
                transport(command -> new ProcessBuilder("sh", "-c", "echo no-server >&2; exit 1").start())) {
            transport.observe(seen::add);
            CommandResult result = transport.execute(CommandRequest.of(
                    List.of("tmux"), List.of("display-message", "secret-token"), Duration.ofSeconds(5)));

            assertEquals(1, result.exitCode());
            OperationReport report = seen.get(0);
            assertEquals(List.of("display-message"), report.verbs());
            assertEquals(DispatchOutcome.COMPLETE, report.certainty());
            assertEquals(OptionalInt.of(1), report.exitCode());
            assertTrue(report.boundedError().contains("no-server"));
            assertFalse(report.toString().contains("secret-token"));
            assertFalse(report.boundedError().contains("secret-token"));
            assertTrue(report.queued().toNanos() >= 0);
            assertTrue(report.elapsed().toNanos() >= 0);
        }
    }

    @Test
    void aProcessThatNeverStartsIsNotDispatched() {
        List<OperationReport> seen = new ArrayList<>();
        try (ProcessTransport transport = transport(command -> {
            throw new IOException("down");
        })) {
            transport.observe(seen::add);
            try {
                transport.execute(CommandRequest.of(
                        List.of("tmux"), List.of("new-session", "secret-token"), Duration.ofSeconds(5)));
            } catch (TmuxTransportException failure) {
                assertEquals(DispatchOutcome.NOT_DISPATCHED, failure.outcome());
            }

            OperationReport report = seen.get(0);
            assertEquals(List.of("new-session"), report.verbs());
            assertEquals(DispatchOutcome.NOT_DISPATCHED, report.certainty());
            assertTrue(report.exitCode().isEmpty());
            assertFalse(report.toString().contains("secret-token"));
        }
    }

    private static ProcessTransport transport(ProcessTransport.ProcessStarter starter) {
        return new ProcessTransport(1, 1024, starter, System::nanoTime, 1_000_000L);
    }
}
