package io.github.libtmux.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/** Serializes per-session sends because the pinned SDK's stdio sink rejects concurrent emissions. */
final class SerializedTransportProvider implements McpServerTransportProvider {

    private final McpServerTransportProvider delegate;

    SerializedTransportProvider(McpServerTransportProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory factory) {
        delegate.setSessionFactory(transport -> factory.create(serialize(transport)));
    }

    static McpServerTransport serialize(McpServerTransport transport) {
        return new SerializedTransport(transport);
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
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private static final class SerializedTransport implements McpServerTransport {

        private final McpServerTransport delegate;
        private final Object sends = new Object();
        private final ArrayDeque<PendingSend> pending = new ArrayDeque<>();
        private boolean sending;

        SerializedTransport(McpServerTransport delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.create(sink -> enqueue(new PendingSend(message, sink)));
        }

        private void enqueue(PendingSend added) {
            synchronized (sends) {
                pending.addLast(added);
                if (sending) {
                    return;
                }
                sending = true;
            }
            sendNext();
        }

        private void sendNext() {
            PendingSend next;
            synchronized (sends) {
                next = pending.removeFirst();
            }
            try {
                delegate.sendMessage(next.message())
                        .subscribe(ignored -> {}, failure -> finish(next, failure), () -> finish(next, null));
            } catch (RuntimeException | Error failure) {
                finish(next, failure);
            }
        }

        private void finish(PendingSend completed, @Nullable Throwable failure) {
            boolean hasNext;
            synchronized (sends) {
                hasNext = !pending.isEmpty();
                sending = hasNext;
            }
            if (failure == null) {
                completed.sink().success();
            } else {
                completed.sink().error(failure);
            }
            if (hasNext) {
                sendNext();
            }
        }

        @Override
        public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
            return delegate.unmarshalFrom(value, type);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }

        private record PendingSend(McpSchema.JSONRPCMessage message, MonoSink<Void> sink) {}
    }
}
