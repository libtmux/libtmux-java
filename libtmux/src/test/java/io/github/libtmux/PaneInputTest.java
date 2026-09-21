package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
}
