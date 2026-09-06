package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@ExtendWith(TmuxExtension.class)
final class ServerRequestTimeoutTest {

    @Test
    void serverRequestsRetainTheSdkDefaultBound(Server server) {
        CapturingProvider provider = new CapturingProvider();
        McpSyncServer mcp = TmuxMcpServer.serving(server, provider);
        RecordingScheduler scheduler = new RecordingScheduler();
        Schedulers.Snapshot snapshot = Schedulers.setFactoryWithSnapshot(scheduler);
        try {
            McpServerSession session = provider.factory().create(new NoReplyTransport());
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Disposable request = session.sendRequest("test/request", Map.of(), new TypeRef<Object>() {})
                    .subscribe(ignored -> {}, failure::set);

            assertEquals(Duration.ofSeconds(10), scheduler.requested());
            assertInstanceOf(TimeoutException.class, failure.get());
            request.dispose();
        } finally {
            Schedulers.resetFrom(snapshot);
            mcp.close();
        }
    }

    private static final class CapturingProvider implements McpServerTransportProvider {

        private final AtomicReference<McpServerSession.Factory> factory = new AtomicReference<>();

        @Override
        public void setSessionFactory(McpServerSession.Factory value) {
            factory.set(value);
        }

        McpServerSession.Factory factory() {
            return Objects.requireNonNull(factory.get());
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }

    private static final class NoReplyTransport implements McpServerTransport {

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }

    private static final class RecordingScheduler implements Scheduler, Schedulers.Factory {

        private final AtomicReference<Duration> requested = new AtomicReference<>();

        @Override
        public Disposable schedule(Runnable task) {
            return Schedulers.immediate().schedule(task);
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            requested.set(Duration.ofNanos(unit.toNanos(delay)));
            task.run();
            return Disposables.disposed();
        }

        @Override
        public Scheduler.Worker createWorker() {
            return Schedulers.immediate().createWorker();
        }

        @Override
        public Scheduler newParallel(int parallelism, ThreadFactory threadFactory) {
            return this;
        }

        Duration requested() {
            return Objects.requireNonNull(requested.get());
        }
    }
}
