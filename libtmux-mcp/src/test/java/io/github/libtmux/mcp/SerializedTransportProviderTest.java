package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

final class SerializedTransportProviderTest {

    @Test
    void sendsOneMessageAtATime() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        AtomicInteger completed = new AtomicInteger();

        Disposable first = transport
                .sendMessage(notification("first"))
                .subscribe(ignored -> {}, failure -> {}, completed::incrementAndGet);
        Disposable second = transport
                .sendMessage(notification("second"))
                .subscribe(ignored -> {}, failure -> {}, completed::incrementAndGet);

        assertEquals(1, delegate.started(), "the second send overlapped the first");
        delegate.succeed(0);
        assertEquals(2, delegate.started(), "the queued send did not start after the first");
        delegate.succeed(1);
        assertEquals(2, completed.get());

        first.dispose();
        second.dispose();
    }

    @Test
    void failedSendReleasesTheNextMessage() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        Disposable first = transport
                .sendMessage(notification("first"))
                .subscribe(ignored -> {}, failure -> failed.incrementAndGet());
        Disposable second = transport
                .sendMessage(notification("second"))
                .subscribe(ignored -> {}, failure -> {}, completed::incrementAndGet);

        delegate.fail(0);
        assertEquals(1, failed.get());
        assertEquals(2, delegate.started(), "a failed send left the queue stalled");
        delegate.succeed(1);
        assertEquals(1, completed.get());

        first.dispose();
        second.dispose();
    }

    private static McpSchema.JSONRPCNotification notification(String value) {
        return new McpSchema.JSONRPCNotification("test/notification", value);
    }

    private static final class PausingTransport implements McpServerTransport {

        private final List<Sinks.One<Void>> completions = new ArrayList<>();

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.defer(() -> {
                Sinks.One<Void> completion = Sinks.one();
                completions.add(completion);
                return completion.asMono();
            });
        }

        int started() {
            return completions.size();
        }

        void succeed(int index) {
            completions.get(index).tryEmitEmpty();
        }

        void fail(int index) {
            completions.get(index).tryEmitError(new IllegalStateException("send failed"));
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
}
