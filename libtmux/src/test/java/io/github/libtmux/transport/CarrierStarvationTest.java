package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The gate that decides how the transport drains, run under a scheduler with exactly one carrier.
 *
 * <p>A library does not own the virtual-thread scheduler. Any unrelated code in the same JVM that
 * blocks inside a {@code synchronized} block holds a carrier for the duration, and JDK 21 has no
 * unpinned monitor blocking — that arrives with <a href="https://openjdk.org/jeps/491">JEP 491</a>.
 * A transport whose drains are virtual threads then cannot run them, its child's pipe fills, and
 * the child stops instead of exiting.
 *
 * <p>The companion red proof exists because a gate that has never failed is not a gate. If the
 * fixture ever stops actually starving the carrier, the red proof passes its flood and fails,
 * rather than leaving the real gate quietly vacuous.
 *
 * <p>Most of this class is tagged {@code carrier} and runs in a fork pinned to one carrier. The
 * untagged case runs in the ordinary suite, under the ordinary scheduler, and is what makes the red
 * proof a statement about starvation rather than about the flood being too large to survive.
 */
final class CarrierStarvationTest {

    private static final int FLOOD_BYTES = 262_144;
    private static final Duration DEADLINE = Duration.ofSeconds(5);

    private static final String FLOOD = "head -c " + FLOOD_BYTES + " /dev/zero | tr '\\0' a &" + " head -c "
            + FLOOD_BYTES + " /dev/zero | tr '\\0' b >&2; wait";

    private static CommandRequest flood() {
        return new CommandRequest(List.of("/bin/sh"), List.of("-c", FLOOD), DEADLINE);
    }

    @Test
    @Tag("carrier")
    void theSchedulerReallyHasOneCarrier() {
        assertEquals(
                "1",
                System.getProperty("jdk.virtualThreadScheduler.maxPoolSize"),
                "this suite is meaningless unless it runs under a one-carrier scheduler");
    }

    @Test
    @Tag("carrier")
    void platformPumpsDrainThroughAStarvedCarrier() throws Exception {
        try (CarrierHog hog = new CarrierHog();
                ProcessTransport transport = new ProcessTransport(2)) {
            assertTrue(hog.isHolding(), "the fixture did not manage to pin the only carrier");

            CommandResult result = transport.execute(flood());

            assertEquals(0, result.exitCode());
            assertEquals(FLOOD_BYTES, result.stdout().get(0).length());
            assertEquals(FLOOD_BYTES, result.stderr().get(0).length());
        }
    }

    /**
     * The red proof. Drains that need a virtual thread cannot mount while the only carrier is held,
     * so the child fills its pipe and dies on its deadline. This is the measurement that separated
     * the two designs; a pinning recording reports zero events for both and cannot see it.
     */
    @Test
    @Tag("carrier")
    void drainsThatNeedACarrierCannotRunAtAll() throws Exception {
        try (CarrierHog hog = new CarrierHog();
                VirtualDrainTransport transport = new VirtualDrainTransport()) {
            assertTrue(hog.isHolding(), "the fixture did not manage to pin the only carrier");

            TmuxTransportException failure =
                    assertThrows(TmuxTransportException.class, () -> transport.execute(flood()));

            assertEquals(DispatchOutcome.UNKNOWN, failure.outcome());
        }
    }

    /**
     * Deliberately untagged, so it runs under the ordinary scheduler. Without it the red proof
     * would only show that virtual drains failed, not that starvation is why.
     */
    @Test
    void theSameFloodSucceedsOnVirtualDrainsWithAnOrdinaryScheduler() throws Exception {
        try (VirtualDrainTransport transport = new VirtualDrainTransport()) {
            CommandResult result = transport.execute(flood());

            assertEquals(
                    FLOOD_BYTES,
                    result.stdout().get(0).length(),
                    "the flood itself is survivable, so starvation is what the red proof measured");
        }
    }

    // -------------------------------------------------------------------------------- fixtures

    /** Pins the only carrier: a virtual thread blocked entering a monitor a platform thread owns. */
    private static final class CarrierHog implements AutoCloseable {

        private final Object monitor = new Object();
        private final CountDownLatch owned = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread owner;
        private final Thread hog;

        CarrierHog() throws InterruptedException {
            owner = Thread.ofPlatform().start(() -> {
                synchronized (monitor) {
                    owned.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            owned.await();
            hog = Thread.ofVirtual().start(() -> {
                synchronized (monitor) {
                    // Reaching this at all means the monitor was released, so the hog is done.
                }
            });
            // Give the hog time to mount the carrier and block on the monitor.
            Thread.sleep(500);
        }

        boolean isHolding() {
            return hog.isAlive() && owner.isAlive();
        }

        @Override
        public void close() {
            release.countDown();
            try {
                owner.join(TimeUnit.SECONDS.toMillis(20));
                hog.join(TimeUnit.SECONDS.toMillis(20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Drains each pipe on a virtual thread. Kept in the test tree only: it is the design this gate
     * rejects, and the assertion above is what rejects it.
     */
    private static final class VirtualDrainTransport implements TmuxTransport {

        @Override
        public CommandResult execute(CommandRequest request) {
            Process process;
            try {
                process = new ProcessBuilder(request.commandLine()).start();
            } catch (IOException e) {
                throw new TmuxTransportException("could not start", DispatchOutcome.NOT_DISPATCHED, e);
            }
            BlockingQueue<byte[]> out = new ArrayBlockingQueue<>(1);
            BlockingQueue<byte[]> err = new ArrayBlockingQueue<>(1);
            Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
            Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));
            try {
                if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new TmuxTransportException("child exceeded its deadline", DispatchOutcome.UNKNOWN, null);
                }
                byte[] stdout = take(out, request.timeout());
                byte[] stderr = take(err, request.timeout());
                return new CommandResult(
                        process.exitValue(), OutputDecoder.stdoutLines(stdout), OutputDecoder.stderrLines(stderr));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TmuxTransportException("interrupted", DispatchOutcome.UNKNOWN, e);
            } finally {
                process.destroyForcibly();
            }
        }

        private static byte[] take(BlockingQueue<byte[]> channel, Duration timeout) throws InterruptedException {
            byte[] bytes = channel.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (bytes == null) {
                throw new TmuxTransportException("drain never finished", DispatchOutcome.UNKNOWN, null);
            }
            return bytes;
        }

        private static void drain(InputStream source, BlockingQueue<byte[]> sink) {
            try {
                sink.add(source.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {}
    }
}
