package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void aSlowClientHasFiniteMessageAdmission() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        AtomicInteger refused = new AtomicInteger();
        List<Disposable> sends = new ArrayList<>();

        for (int index = 0; index < 257; index++) {
            sends.add(transport
                    .sendMessage(notification("message-" + index))
                    .subscribe(ignored -> {}, failure -> refused.incrementAndGet()));
        }

        assertEquals(1, delegate.started(), "a stalled delegate must still have only one active send");
        assertEquals(1, refused.get(), "the send beyond the 256-message bound was retained");

        transport.close();
        sends.forEach(Disposable::dispose);
    }

    @Test
    void aSingleMessageCannotExceedByteAdmission() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        AtomicReference<Throwable> refused = new AtomicReference<>();

        Disposable send = transport
                .sendMessage(notification("x".repeat(17 * 1024 * 1024)))
                .subscribe(ignored -> {}, refused::set);

        assertInstanceOf(IllegalStateException.class, refused.get());
        assertEquals(0, delegate.started(), "an oversized message reached the delegate");

        send.dispose();
        transport.close();
    }

    @Test
    void cancellingAQueuedSendRemovesIt() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        Disposable first = transport.sendMessage(notification("first")).subscribe();
        Disposable cancelled = transport.sendMessage(notification("cancelled")).subscribe();

        cancelled.dispose();
        delegate.succeed(0);

        assertEquals(1, delegate.started(), "the cancelled send was handed to the delegate");

        first.dispose();
        transport.close();
    }

    @Test
    void cancellingAPromotedSendBeforeItStartsRemovesIt() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        AtomicReference<Disposable> promoted = new AtomicReference<>();
        McpSchema.JSONRPCNotification firstMessage = notification("first");
        McpSchema.JSONRPCNotification cancelledMessage = notification("cancelled");
        McpSchema.JSONRPCNotification thirdMessage = notification("third");
        Disposable first = transport
                .sendMessage(firstMessage)
                .subscribe(ignored -> {}, failure -> {}, () -> promoted.get().dispose());
        promoted.set(transport.sendMessage(cancelledMessage).subscribe());
        Disposable third = transport.sendMessage(thirdMessage).subscribe();

        delegate.succeed(0);

        assertEquals(2, delegate.started(), "cancellation did not release the next queued send");
        assertSame(thirdMessage, delegate.message(1), "the cancelled promoted send reached the delegate");
        delegate.succeed(1);

        first.dispose();
        third.dispose();
        transport.close();
    }

    @Test
    void closeFailsEveryAdmittedSendAndRefusesAnother() {
        PausingTransport delegate = new PausingTransport();
        McpServerTransport transport = SerializedTransportProvider.serialize(delegate);
        AtomicInteger failed = new AtomicInteger();
        Disposable first = transport
                .sendMessage(notification("first"))
                .subscribe(ignored -> {}, failure -> failed.incrementAndGet());
        Disposable second = transport
                .sendMessage(notification("second"))
                .subscribe(ignored -> {}, failure -> failed.incrementAndGet());

        transport.close();

        assertEquals(2, failed.get(), "close left an admitted send unresolved");
        assertEquals(1, delegate.closed());
        delegate.succeed(0);
        assertEquals(1, delegate.started(), "completion after close started a queued send");

        AtomicReference<Throwable> afterClose = new AtomicReference<>();
        Disposable refused =
                transport.sendMessage(notification("after-close")).subscribe(ignored -> {}, afterClose::set);
        assertInstanceOf(IllegalStateException.class, afterClose.get());

        first.dispose();
        second.dispose();
        refused.dispose();
    }

    private static McpSchema.JSONRPCNotification notification(String value) {
        return new McpSchema.JSONRPCNotification("test/notification", value);
    }

    private static final class PausingTransport implements McpServerTransport {

        private final List<Sinks.One<Void>> completions = new ArrayList<>();
        private final List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.defer(() -> {
                Sinks.One<Void> completion = Sinks.one();
                completions.add(completion);
                messages.add(message);
                return completion.asMono();
            });
        }

        int started() {
            return completions.size();
        }

        McpSchema.JSONRPCMessage message(int index) {
            return messages.get(index);
        }

        void succeed(int index) {
            completions.get(index).tryEmitEmpty();
        }

        void fail(int index) {
            completions.get(index).tryEmitError(new IllegalStateException("send failed"));
        }

        int closed() {
            return closed.get();
        }

        @Override
        public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
