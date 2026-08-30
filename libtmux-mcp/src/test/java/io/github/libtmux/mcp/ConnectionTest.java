package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class ConnectionTest {

    @Test
    void aClientListingCannotObserveAHalfHiddenWatcher() throws Exception {
        TmuxTransport unused = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                throw new AssertionError("this test dispatches no tmux command");
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), unused);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Connection connection =
                    new Connection(server, Caller.nowhere(), Safety.MUTATING, ConcurrentHashMap.newKeySet());
            CountDownLatch attaching = new CountDownLatch(1);
            CountDownLatch hide = new CountDownLatch(1);
            CountDownLatch listing = new CountDownLatch(1);

            var attachment = tasks.submit(() -> connection.changeClients(() -> {
                attaching.countDown();
                await(hide);
                connection.hide("watcher");
            }));
            assertTrue(attaching.await(1, TimeUnit.SECONDS));

            var observed = tasks.submit(() -> {
                listing.countDown();
                return connection.withStableClients(() -> connection.isOurs("watcher"));
            });
            assertTrue(listing.await(1, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> observed.get(100, TimeUnit.MILLISECONDS));
            } finally {
                hide.countDown();
            }
            attachment.get(1, TimeUnit.SECONDS);
            assertTrue(observed.get(1, TimeUnit.SECONDS), "the listing ran before the watcher was hidden");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while arranging the test", e);
        }
    }
}
