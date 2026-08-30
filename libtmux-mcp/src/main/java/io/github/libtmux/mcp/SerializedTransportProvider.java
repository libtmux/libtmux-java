package io.github.libtmux.mcp;

import com.fasterxml.jackson.core.JacksonException;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/** Serializes per-session sends because the pinned SDK's stdio sink rejects concurrent emissions. */
final class SerializedTransportProvider implements McpServerTransportProvider {

    private static final int SEND_CAPACITY = 256;
    private static final long SEND_BYTE_CAPACITY = 16L * 1024 * 1024;

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
        private @Nullable PendingSend active;
        private int admitted;
        private long admittedBytes;
        private boolean closed;

        SerializedTransport(McpServerTransport delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.create(sink -> {
                PendingSend added;
                try {
                    added = new PendingSend(message, sink, encodedBytes(message));
                } catch (RuntimeException failure) {
                    sink.error(failure);
                    return;
                }
                sink.onCancel(() -> cancel(added));
                enqueue(added);
            });
        }

        private void enqueue(PendingSend added) {
            @Nullable Throwable refused = null;
            @Nullable PendingSend next = null;
            synchronized (sends) {
                if (added.cancelled) {
                    return;
                }
                if (closed) {
                    refused = new IllegalStateException("transport is closed");
                } else if (admitted >= SEND_CAPACITY || added.bytes > SEND_BYTE_CAPACITY - admittedBytes) {
                    refused = new IllegalStateException("outbound send capacity exceeded");
                } else {
                    admitted++;
                    admittedBytes += added.bytes;
                    pending.addLast(added);
                    if (active == null) {
                        next = takeNext();
                    }
                }
            }
            if (refused != null) {
                added.sink.error(refused);
            } else if (next != null) {
                start(next);
            }
        }

        private void cancel(PendingSend cancelled) {
            synchronized (sends) {
                cancelled.cancelled = true;
                if (pending.remove(cancelled)) {
                    release(cancelled);
                }
            }
        }

        private PendingSend takeNext() {
            PendingSend next = pending.removeFirst();
            active = next;
            return next;
        }

        private void start(PendingSend next) {
            try {
                synchronized (sends) {
                    if (closed || active != next) {
                        return;
                    }
                    delegate.sendMessage(next.message)
                            .subscribe(ignored -> {}, failure -> finish(next, failure), () -> finish(next, null));
                }
            } catch (RuntimeException | Error failure) {
                finish(next, failure);
            }
        }

        private void finish(PendingSend completed, @Nullable Throwable failure) {
            @Nullable PendingSend next = null;
            synchronized (sends) {
                if (active != completed) {
                    return;
                }
                active = null;
                release(completed);
                if (!pending.isEmpty()) {
                    next = takeNext();
                }
            }
            if (failure == null) {
                completed.sink.success();
            } else {
                completed.sink.error(failure);
            }
            if (next != null) {
                start(next);
            }
        }

        private void release(PendingSend released) {
            admitted--;
            admittedBytes -= released.bytes;
        }

        private List<PendingSend> abandon() {
            synchronized (sends) {
                if (closed) {
                    return List.of();
                }
                closed = true;
                List<PendingSend> abandoned = new ArrayList<>(admitted);
                if (active != null) {
                    abandoned.add(active);
                    active = null;
                }
                abandoned.addAll(pending);
                pending.clear();
                admitted = 0;
                admittedBytes = 0;
                return abandoned;
            }
        }

        private static void fail(List<PendingSend> abandoned) {
            abandoned.forEach(send -> send.sink.error(new IllegalStateException("transport is closed")));
        }

        private static long encodedBytes(McpSchema.JSONRPCMessage message) {
            try {
                return Answers.JSON.writeValueAsBytes(message).length;
            } catch (JacksonException e) {
                throw new IllegalStateException("could not size outbound message", e);
            }
        }

        @Override
        public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
            return delegate.unmarshalFrom(value, type);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.defer(() -> {
                fail(abandon());
                return delegate.closeGracefully();
            });
        }

        @Override
        public void close() {
            List<PendingSend> abandoned = abandon();
            fail(abandoned);
            delegate.close();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }

        private static final class PendingSend {
            private final McpSchema.JSONRPCMessage message;
            private final MonoSink<Void> sink;
            private final long bytes;
            private boolean cancelled;

            PendingSend(McpSchema.JSONRPCMessage message, MonoSink<Void> sink, long bytes) {
                this.message = message;
                this.sink = sink;
                this.bytes = bytes;
            }
        }
    }
}
