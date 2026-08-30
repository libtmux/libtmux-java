package io.github.libtmux.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/** Makes every way a protocol session can end converge on one callback. */
final class SessionLifetime implements Runnable {

    private final Runnable ended;
    private final ArrayDeque<AutoCloseable> owned = new ArrayDeque<>();
    private final AtomicBoolean signalled = new AtomicBoolean();

    SessionLifetime(Runnable ended) {
        this.ended = Objects.requireNonNull(ended, "ended");
    }

    OutputStream observe(OutputStream output) {
        return new ObservedOutput(output, this);
    }

    McpServerTransportProvider observe(McpServerTransportProvider provider) {
        return new ObservedProvider(provider, this);
    }

    /** Closes a session-scoped resource now or when the first end signal arrives. */
    void own(AutoCloseable resource) {
        Objects.requireNonNull(resource, "resource");
        synchronized (owned) {
            if (!signalled.get()) {
                owned.addLast(resource);
                return;
            }
        }
        close(resource);
    }

    @Override
    public void run() {
        if (signalled.compareAndSet(false, true)) {
            @Nullable Throwable failure = finish(null);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException("could not close protocol session", failure);
            }
        }
    }

    void endAfter(Throwable failure) {
        if (signalled.compareAndSet(false, true)) {
            finish(failure);
        }
    }

    private @Nullable Throwable finish(@Nullable Throwable failure) {
        Cleanup cleanup = new Cleanup(failure);
        cleanup.run(ended);
        AutoCloseable resource;
        while ((resource = takeOwned()) != null) {
            cleanup.close(resource);
        }
        return cleanup.failure();
    }

    private @Nullable AutoCloseable takeOwned() {
        synchronized (owned) {
            return owned.pollLast();
        }
    }

    private Mono<Void> endWith(Mono<Void> closing) {
        return closing.onErrorResume(failure -> {
                    endAfter(failure);
                    return Mono.error(failure);
                })
                .then(Mono.fromRunnable(this));
    }

    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("could not close protocol session resource", failure);
        }
    }

    private record ObservedProvider(McpServerTransportProvider delegate, SessionLifetime lifetime)
            implements McpServerTransportProvider {

        @Override
        public void setSessionFactory(io.modelcontextprotocol.spec.McpServerSession.Factory factory) {
            delegate.setSessionFactory(transport -> factory.create(new ObservedTransport(transport, lifetime)));
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return delegate.notifyClients(method, params);
        }

        @Override
        public Mono<Void> notifyClient(String sessionId, String method, Object params) {
            return delegate.notifyClient(sessionId, method, params);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return lifetime.endWith(delegate.closeGracefully());
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } catch (RuntimeException | Error failure) {
                lifetime.endAfter(failure);
                throw failure;
            }
            lifetime.run();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }

    private record ObservedTransport(McpServerTransport delegate, SessionLifetime lifetime)
            implements McpServerTransport {

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return delegate.sendMessage(message);
        }

        @Override
        public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
            return delegate.unmarshalFrom(value, type);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return lifetime.endWith(delegate.closeGracefully());
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } catch (RuntimeException | Error failure) {
                lifetime.endAfter(failure);
                throw failure;
            }
            lifetime.run();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }

    private static final class ObservedOutput extends OutputStream {

        private final OutputStream delegate;
        private final SessionLifetime lifetime;

        ObservedOutput(OutputStream delegate, SessionLifetime lifetime) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.lifetime = lifetime;
        }

        @Override
        public void write(int value) throws IOException {
            attempt(() -> delegate.write(value));
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            attempt(() -> delegate.write(bytes));
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            attempt(() -> delegate.write(bytes, offset, length));
        }

        @Override
        public void flush() throws IOException {
            attempt(delegate::flush);
            if (delegate instanceof PrintStream stream && stream.checkError()) {
                IOException failure = new IOException("protocol output is no longer writable");
                lifetime.endAfter(failure);
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } catch (IOException | RuntimeException | Error failure) {
                lifetime.endAfter(failure);
                throw failure;
            }
            lifetime.run();
        }

        private void attempt(IoAction action) throws IOException {
            try {
                action.run();
            } catch (IOException | RuntimeException | Error failure) {
                lifetime.endAfter(failure);
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {

        void run() throws IOException;
    }
}
