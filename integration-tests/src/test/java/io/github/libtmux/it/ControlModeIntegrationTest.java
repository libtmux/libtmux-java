package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlReply;
import io.github.libtmux.control.PaneOutput;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A persistent control client, and the property that makes it worth having.
 *
 * <p>A semicolon group is discarded after its first failure, so a client has to infer which command
 * failed and which never ran. Control-mode requests are independent: the request after a failure
 * still runs, and every reply carries the request that produced it.
 */
@ExtendWith(TmuxExtension.class)
final class ControlModeIntegrationTest {

    private static ControlClient attach(Server server) {
        Session session = server.sessions().get(0);
        return ControlClient.attach(server.config(), session.id());
    }

    @Test
    void aCommandGetsItsOwnReply(Server server) {
        try (ControlClient client = attach(server)) {

            ControlReply reply = client.send("display-message", "-p", "hello");

            assertTrue(reply.succeeded());
            assertEquals(List.of("hello"), reply.lines());
        }
    }

    /** The property a semicolon group cannot offer: a failure discards nothing behind it. */
    @Test
    void aFailureDoesNotDiscardTheRequestsBehindIt(Server server) {
        try (ControlClient client = attach(server)) {
            ControlReply first = client.send("display-message", "-p", "before");
            ControlReply failed = client.send("select-pane", "-t", "=missing");
            ControlReply after = client.send("display-message", "-p", "after");

            assertEquals(OperationOutcome.COMPLETE, first.outcome());
            assertEquals(OperationOutcome.FAILED, failed.outcome());
            assertEquals(OperationOutcome.COMPLETE, after.outcome(), "the request after a failure still ran");
            assertEquals(List.of("after"), after.lines());
        }
    }

    @Test
    void aFailedCommandCarriesTmuxsReason(Server server) {
        try (ControlClient client = attach(server)) {
            ControlReply reply = client.send("select-pane", "-t", "=missing");

            assertFalse(reply.succeeded());
            assertTrue(
                    reply.lines().stream().anyMatch(line -> line.contains("missing")),
                    "the caller needs tmux's reason: " + reply.lines());
        }
    }

    @Test
    void anArgumentSurvivesTmuxsOwnLexer(Server server) {
        try (ControlClient client = attach(server)) {
            assertEquals(
                    List.of("has spaces"),
                    client.send("display-message", "-p", "has spaces").lines());
            assertEquals(
                    List.of("it's quoted"),
                    client.send("display-message", "-p", "it's quoted").lines());
            assertEquals(
                    List.of("semi;colon"),
                    client.send("display-message", "-p", "semi;colon").lines(),
                    "a semicolon inside an argument is not a command separator");
            assertEquals(
                    List.of("trailing;"),
                    client.send("display-message", "-p", "trailing\\;").lines(),
                    "an escaped trailing semicolon is part of the argument, not a separator");
        }
    }

    /**
     * A reply is framed per command, so a request that is several commands has several replies and
     * this client can only account for one of them. The rest go to whoever asks next.
     *
     * <p>Both spellings are refused, because tmux reads both: a semicolon standing alone between two
     * commands, and one ending an argument of the first.
     */
    @Test
    void aRequestOfSeveralCommandsIsRefusedRatherThanMisframed(Server server) {
        try (ControlClient client = attach(server)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.send(
                            List.of("display-message", "-p", "first", ";", "display-message", "-p", "second")),
                    "a semicolon standing alone separates two commands");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.send(List.of("new-window", "-d", "-n", "grouped;", "list-windows")),
                    "a semicolon ending an argument separates two commands just as well");

            assertEquals(
                    List.of("still answering"),
                    client.send("display-message", "-p", "still answering").lines(),
                    "a refusal writes nothing, so the stream is still in step");
        }
    }

    @Test
    void repliesStayMatchedToTheirRequestsUnderConcurrency(Server server) throws Exception {
        try (ControlClient client = attach(server)) {
            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                List<Future<ControlReply>> pending = new ArrayList<>();
                for (int index = 0; index < 40; index++) {
                    String expected = "reply-" + index;
                    pending.add(callers.submit(() -> client.send("display-message", "-p", expected)));
                }
                for (Future<ControlReply> future : pending) {
                    ControlReply reply = future.get(60, TimeUnit.SECONDS);
                    assertTrue(reply.succeeded());
                    assertEquals(1, reply.lines().size());
                    assertTrue(
                            reply.lines().get(0).startsWith("reply-"),
                            reply.lines().toString());
                }
            } finally {
                callers.shutdownNow();
            }
        }
    }

    @Test
    void terminalOutputArrivesWithoutBeingAsked(Server server) throws Exception {
        try (ControlClient client = attach(server)) {
            List<PaneOutput> seen = new CopyOnWriteArrayList<>();
            client.onOutput(seen::add);

            client.send("send-keys", "-t", "libtmux", "echo control-mode-saw-this", "Enter");

            assertTrue(
                    await(() -> seen.stream().anyMatch(output -> output.data().contains("control-mode-saw-this"))),
                    "attaching is what makes tmux push output, and it did not arrive");
        }
    }

    /**
     * Listeners run on the reader thread, which is also the only thread that resolves replies. A
     * listener that threw would end it, and the client would then answer nothing at all — every
     * later request timing out for a reason belonging to somebody else's callback.
     */
    @Test
    void aListenerThatThrowsDoesNotTakeTheClientDownWithIt(Server server) throws Exception {
        try (ControlClient client = attach(server)) {
            List<PaneOutput> seen = new CopyOnWriteArrayList<>();
            client.onOutput(output -> {
                throw new IllegalStateException("this listener is broken");
            });
            client.onOutput(seen::add);

            client.send("send-keys", "-t", "libtmux", "echo listener-survived-this", "Enter");

            assertTrue(
                    await(() -> seen.stream().anyMatch(output -> output.data().contains("listener-survived-this"))),
                    "a listener registered after the broken one still has to be told");
            assertEquals(
                    List.of("still answering"),
                    client.send("display-message", "-p", "still answering").lines(),
                    "the reader survived, so replies still arrive");
        }
    }

    @Test
    void useAfterCloseIsRejected(Server server) {
        ControlClient client = attach(server);
        client.close();

        assertThrows(IllegalStateException.class, () -> client.send("display-message", "-p", "no"));
    }

    @Test
    void closeIsIdempotent(Server server) {
        ControlClient client = attach(server);

        client.close();
        client.close();
    }

    /**
     * A request nobody answered is unanswered, not failed. No ordinary tmux command can produce
     * this — control mode replies as soon as it queues a command, even a blocking one — so the
     * server is stopped outright to make the reply genuinely never arrive.
     */
    @Test
    void aRequestThatIsNeverAnsweredIsUnknownRatherThanFailed(Server server) throws Exception {
        String pid = server.cmd("display-message", "-p", "#{pid}").stdout().get(0);
        try (ControlClient client = attach(server)) {
            signal("-STOP", pid);
            try {
                ControlReply reply =
                        client.send(List.of("display-message", "-p", "unanswerable"), Duration.ofMillis(500));

                assertEquals(
                        OperationOutcome.UNKNOWN,
                        reply.outcome(),
                        "tmux may well have run it; nothing came back to say so");
                assertEquals(List.of(), reply.lines());
            } finally {
                signal("-CONT", pid);
            }
        }
    }

    /** Proves the stopped server is what withheld the reply, rather than the client being broken. */
    @Test
    void theSameRequestIsAnsweredWhenTheServerIsRunning(Server server) {
        try (ControlClient client = attach(server)) {
            ControlReply reply = client.send(List.of("display-message", "-p", "unanswerable"), Duration.ofMillis(500));

            assertEquals(OperationOutcome.COMPLETE, reply.outcome());
            assertEquals(List.of("unanswerable"), reply.lines());
        }
    }

    private static void signal(String signal, String pid) throws Exception {
        Process kill = new ProcessBuilder("kill", signal, pid).start();
        assertTrue(kill.waitFor(20, TimeUnit.SECONDS) && kill.exitValue() == 0, "could not " + signal + " tmux");
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
