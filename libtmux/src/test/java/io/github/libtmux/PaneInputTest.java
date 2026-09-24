package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class PaneInputTest {

    @Test
    void anotherThreadCannotHoldTheSamePane() throws Exception {
        ServerIdentity identity = ServerIdentity.of("local", ServerEndpoint.socketPath(Path.of("/tmp/lease")));
        PaneId pane = new PaneId("%9");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread owner = Thread.startVirtualThread(() -> {
            try (PaneInput.Lease input = PaneInput.hold(identity, pane)) {
                assertTrue(input != null);
                held.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("owner was not released");
                }
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> PaneInput.hold(identity, pane));
        release.countDown();
        owner.join(5_000);
        assertNull(failure.get());
        try (PaneInput.Lease after = PaneInput.hold(identity, pane)) {
            assertTrue(after != null);
        }
    }

    @Test
    void anotherThreadMayCloseTheLease() throws Exception {
        ServerIdentity identity = ServerIdentity.of("local", ServerEndpoint.socketPath(Path.of("/tmp/lease-close")));
        PaneId pane = new PaneId("%8");
        PaneInput.Lease lease = PaneInput.hold(identity, pane);
        Thread releaser = Thread.startVirtualThread(lease::close);
        releaser.join(5_000);
        try (PaneInput.Lease after = PaneInput.hold(identity, pane)) {
            assertTrue(after != null);
        }
    }

    @Test
    void anInterruptEntersARunHold() throws Exception {
        ServerIdentity identity =
                ServerIdentity.of("local", ServerEndpoint.socketPath(Path.of("/tmp/lease-interrupt")));
        PaneId pane = new PaneId("%4");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread owner = Thread.startVirtualThread(() -> {
            try (PaneInput.Lease input = PaneInput.holdInterruptible(identity, pane)) {
                assertTrue(input != null);
                held.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("owner was not released");
                }
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> PaneInput.hold(identity, pane));
        assertTrue(String.valueOf(blocked.getMessage()).contains("owned by another thread"));
        try (PaneInput.Lease entered = PaneInput.enterInterrupt(identity, pane)) {
            assertTrue(entered != null);
            try (PaneInput.Lease sending = PaneInput.hold(identity, pane)) {
                assertTrue(sending != null);
            }
            assertTrue(String.valueOf(holdFromAnotherThread(identity, pane)).contains("owned by another thread"));
        }
        assertTrue(String.valueOf(holdFromAnotherThread(identity, pane)).contains("owned by another thread"));
        release.countDown();
        owner.join(5_000);
        assertNull(failure.get());
    }

    private static Throwable holdFromAnotherThread(ServerIdentity identity, PaneId pane) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = Thread.startVirtualThread(() -> {
            try {
                PaneInput.hold(identity, pane).close();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        contender.join(5_000);
        return failure.get();
    }

    @Test
    void theOwnerMayStopItsOwnRun() throws Exception {
        ServerIdentity identity = ServerIdentity.of("local", ServerEndpoint.socketPath(Path.of("/tmp/lease-owner")));
        PaneId pane = new PaneId("%6");
        try (PaneInput.Lease run = PaneInput.holdInterruptible(identity, pane)) {
            assertTrue(run != null);
            try (PaneInput.Lease stop = PaneInput.enterInterrupt(identity, pane)) {
                assertTrue(stop != null);
            }
            assertTrue(String.valueOf(holdFromAnotherThread(identity, pane)).contains("owned by another thread"));
        }
    }

    @Test
    void aNormalHoldRejectsAnInterrupt() throws Exception {
        ServerIdentity identity = ServerIdentity.of("local", ServerEndpoint.socketPath(Path.of("/tmp/lease-closed")));
        PaneId pane = new PaneId("%5");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread owner = Thread.startVirtualThread(() -> {
            try (PaneInput.Lease input = PaneInput.hold(identity, pane)) {
                assertTrue(input != null);
                held.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("owner was not released");
                }
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        IllegalStateException rejected =
                assertThrows(IllegalStateException.class, () -> PaneInput.enterInterrupt(identity, pane));
        assertTrue(String.valueOf(rejected.getMessage()).contains("not open to an interrupt"));
        release.countDown();
        owner.join(5_000);
        assertNull(failure.get());
    }

    @Test
    void theSameThreadMayHoldAgain() {
        ServerIdentity identity = ServerIdentity.of("local", ServerEndpoint.socketPath(Path.of("/tmp/lease-re")));
        PaneId pane = new PaneId("%3");
        try (PaneInput.Lease outer = PaneInput.hold(identity, pane)) {
            try (PaneInput.Lease inner = PaneInput.hold(identity, pane)) {
                assertTrue(outer != null && inner != null);
                assertThrows(IllegalStateException.class, () -> {
                    AtomicReference<Throwable> other = new AtomicReference<>();
                    Thread contender = Thread.startVirtualThread(() -> {
                        try {
                            PaneInput.hold(identity, pane).close();
                        } catch (RuntimeException e) {
                            other.set(e);
                        }
                    });
                    contender.join();
                    if (other.get() != null) {
                        throw other.get();
                    }
                });
            }
        }
    }

    @Test
    void everyPaneWriterRefusesAPaneAnotherThreadHolds() throws Exception {
        try (Server server = TypedTextTest.onePaneFixture(new PaneEcho())) {
            Pane pane = server.panes().getFirst();
            Map<String, Executable> writers = new LinkedHashMap<>();
            writers.put("sendKeys", () -> pane.sendKeys(List.of("typed")));
            writers.put("sendLiteral", () -> pane.sendLiteral(List.of("typed")));
            writers.put("paste", () -> pane.paste("typed"));
            writers.put("pasteBuffer", () -> pane.pasteBuffer("typed"));
            writers.put("run", () -> pane.run("true", Duration.ofSeconds(1)));
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread owner = Thread.startVirtualThread(() -> {
                try (PaneInput.Lease input = PaneInput.hold(pane)) {
                    assertTrue(input != null);
                    held.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("owner was not released");
                    }
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                writers.forEach((name, write) ->
                        assertThrows(IllegalStateException.class, write, name + " wrote into a held pane"));
            } finally {
                release.countDown();
                owner.join(5_000);
            }
            assertNull(failure.get());
        }
    }
}
