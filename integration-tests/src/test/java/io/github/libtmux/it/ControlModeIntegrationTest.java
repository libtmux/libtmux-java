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
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutput;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
                    client.send("display-message", "-p", "trailing;").lines(),
                    "a semicolon ending an argument is data, not a separator");
        }
    }

    /**
     * A reply is framed per command, so this client sends exactly one. An argv is one command by
     * construction: every word is quoted, so nothing a caller passes can open a second one and
     * strand its reply for whoever asks next.
     */
    @Test
    void anArgumentCannotOpenASecondCommandAndStrandItsReply(Server server) {
        try (ControlClient client = attach(server)) {
            assertEquals(
                    List.of("first ; display-message -p second"),
                    client.send(List.of("display-message", "-p", "first ; display-message -p second"))
                            .lines());

            assertEquals(
                    List.of("still answering"),
                    client.send("display-message", "-p", "still answering").lines(),
                    "one command in, one reply out, so the stream is still in step");
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
        try (ControlClient client = attach(server);
                EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {

            client.send("send-keys", "-t", "libtmux", "echo control-mode-saw-this", "Enter");

            assertTrue(
                    awaitOutput(output, "control-mode-saw-this"),
                    "attaching is what makes tmux push output, and it did not arrive");
        }
    }

    @Test
    void anIdleSubscriberDoesNotDelayAnotherSubscriberOrReplies(Server server) throws Exception {
        try (ControlClient client = attach(server);
                EventSubscription<PaneOutput> idle = client.subscribeOutput(1);
                EventSubscription<PaneOutput> active = client.subscribeOutput(32)) {
            assertFalse(idle.isClosed());

            client.send("send-keys", "-t", "libtmux", "echo active-subscriber-saw-this", "Enter");

            assertTrue(
                    awaitOutput(active, "active-subscriber-saw-this"),
                    "the idle subscriber delayed delivery to the active one");
            assertEquals(
                    List.of("still answering"),
                    client.send("display-message", "-p", "still answering").lines(),
                    "the idle subscriber delayed command replies");
        }
    }

    @Test
    void aConsumerCanSendACommandFromItsOwnThread(Server server) throws Exception {
        try (ControlClient client = attach(server);
                EventSubscription<PaneOutput> output = client.subscribeOutput(32)) {
            FutureTask<ControlReply> reentrant = new FutureTask<>(() -> {
                if (!awaitOutput(output, "consumer-can-send")) {
                    throw new IllegalStateException("the triggering output never arrived");
                }
                return client.send("display-message", "-p", "sent-from-consumer");
            });
            Thread consumer = Thread.ofVirtual().start(reentrant);

            client.send("send-keys", "-t", "libtmux", "echo consumer-can-send", "Enter");

            assertEquals(
                    List.of("sent-from-consumer"),
                    reentrant.get(10, TimeUnit.SECONDS).lines());
            consumer.join();
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

    @Test
    void closingTheClientWakesAWaitingSubscriber(Server server) throws Exception {
        ControlClient client = attach(server);
        EventSubscription<PaneOutput> output = client.subscribeOutput(1);
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<Optional<PaneOutput>> waiting = new FutureTask<>(() -> {
            entered.countDown();
            return output.next();
        });
        Thread consumer = Thread.ofVirtual().start(waiting);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));

            client.close();

            assertEquals(Optional.empty(), waiting.get(1, TimeUnit.SECONDS));
        } finally {
            waiting.cancel(true);
            output.close();
            client.close();
            consumer.join();
        }
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
                TmuxTimeoutException failure = assertThrows(
                        TmuxTimeoutException.class,
                        () -> client.send(List.of("display-message", "-p", "unanswerable"), Duration.ofMillis(500)));

                assertEquals(
                        DispatchOutcome.UNKNOWN,
                        failure.outcome(),
                        "tmux may well have run it; nothing came back to say so");
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

    private static boolean awaitOutput(EventSubscription<PaneOutput> output, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Duration remaining = Duration.ofNanos(Math.max(0L, deadline - System.nanoTime()));
            var next = output.next(remaining);
            if (next.isEmpty()) {
                return false;
            }
            if (next.orElseThrow().data().contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
