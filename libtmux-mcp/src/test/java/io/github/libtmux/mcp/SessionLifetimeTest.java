package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

final class SessionLifetimeTest {

    @Test
    void callbackFailureDoesNotHideProtocolOutputFailure() {
        IOException outputFailure = new IOException("client stopped reading");
        IllegalStateException callbackFailure = new IllegalStateException("session-end callback failed");
        OutputStream output = new SessionLifetime(() -> {
                    throw callbackFailure;
                })
                .observe(new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw outputFailure;
                    }
                });

        IOException thrown = assertThrows(IOException.class, () -> output.write(0));

        assertSame(outputFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(callbackFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void blockingOwnedCleanupCannotDelayTheEndSignal() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionLifetime lifetime = new SessionLifetime(ended::countDown);
        lifetime.own(() -> {
            closing.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release cleanup");
            }
        });
        Thread ending = Thread.ofVirtual().start(() -> lifetime.endAfter(new IOException("client disconnected")));
        try {
            assertTrue(closing.await(1, TimeUnit.SECONDS), "owned cleanup never started");
            assertTrue(ended.await(1, TimeUnit.SECONDS), "owned cleanup blocked the session-end signal");
        } finally {
            release.countDown();
            ending.join();
        }
    }

    @Test
    void gracefulCloseReportsSessionEndFailure() {
        IllegalStateException endFailure = new IllegalStateException("session-end callback failed");
        SessionLifetime lifetime = new SessionLifetime(() -> {
            throw endFailure;
        });
        McpServerTransportProvider observed = lifetime.observe(providerClosingWith(Mono.empty()));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> observed.closeGracefully().block());

        assertSame(endFailure, thrown);
    }

    @Test
    void gracefulClosePreservesProviderFailure() {
        IllegalStateException providerFailure = new IllegalStateException("provider close failed");
        IllegalStateException callbackFailure = new IllegalStateException("session-end callback failed");
        IllegalStateException cleanupFailure = new IllegalStateException("owned cleanup failed");
        SessionLifetime lifetime = new SessionLifetime(() -> {
            throw callbackFailure;
        });
        lifetime.own(() -> {
            throw cleanupFailure;
        });
        McpServerTransportProvider observed = lifetime.observe(providerClosingWith(Mono.error(providerFailure)));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> observed.closeGracefully().block());

        assertSame(providerFailure, thrown);
        assertTrue(Arrays.asList(thrown.getSuppressed()).contains(callbackFailure));
        assertTrue(Arrays.asList(thrown.getSuppressed()).contains(cleanupFailure));
    }

    private static McpServerTransportProvider providerClosingWith(Mono<Void> closing) {
        return new McpServerTransportProvider() {
            @Override
            public void setSessionFactory(McpServerSession.Factory factory) {}

            @Override
            public Mono<Void> notifyClients(String method, Object params) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> closeGracefully() {
                return closing;
            }
        };
    }
}
