package io.github.libtmux.mcp;

import io.github.libtmux.SessionId;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.PaneOutput;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/** Owns the control client and loss-aware subscription drains for one tmux session. */
final class WatchAttachment implements AutoCloseable {

    private static final String PANE_STATE = String.join(
            ",", "#{pane_current_command}", "#{pane_current_path}", "#{pane_width}x#{pane_height}", "#{pane_active}");
    private static final int EVENT_CAPACITY = 128;
    private static final long JOIN_MILLIS = 5_000;

    private final Watches owner;
    private final Connection connection;
    private final SessionId session;
    private final ControlClient client;
    private final EventSubscription<PaneOutput> output;
    private final EventSubscription<ControlEvent> events;
    private final @Nullable String clientName;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread outputConsumer;
    private final Thread eventConsumer;

    private WatchAttachment(
            Watches owner,
            Connection connection,
            SessionId session,
            ControlClient client,
            EventSubscription<PaneOutput> output,
            EventSubscription<ControlEvent> events,
            @Nullable String clientName) {
        this.owner = owner;
        this.connection = connection;
        this.session = session;
        this.client = client;
        this.output = output;
        this.events = events;
        this.clientName = clientName;
        this.outputConsumer = Thread.ofVirtual().unstarted(this::consumeOutput);
        this.outputConsumer.setName("libtmux-mcp-output-" + session.value());
        this.eventConsumer = Thread.ofVirtual().unstarted(this::consumeEvents);
        this.eventConsumer.setName("libtmux-mcp-events-" + session.value());
    }

    static WatchAttachment open(Watches owner, Connection connection, SessionId session) {
        return connection.changeClients(() -> openWhileHidden(owner, connection, session));
    }

    private static WatchAttachment openWhileHidden(Watches owner, Connection connection, SessionId session) {
        ControlClient client = ControlClient.attach(
                connection.server().config(),
                session,
                connection.server().config().defaultTimeout());
        String name = null;
        try {
            name = client.send("display-message", "-p", "#{client_name}").lines().stream()
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(null);
            if (name != null) {
                connection.hide(name);
            }
            EventSubscription<PaneOutput> output = client.subscribeOutput(EVENT_CAPACITY);
            EventSubscription<ControlEvent> events = client.subscribeEvents(EVENT_CAPACITY);
            if (!client.watch("state", "%*", PANE_STATE).succeeded()) {
                throw new IllegalStateException("tmux refused the pane-state watch");
            }
            return new WatchAttachment(owner, connection, session, client, output, events, name);
        } catch (RuntimeException e) {
            if (name != null) {
                connection.reveal(name);
            }
            client.close();
            throw e;
        }
    }

    SessionId session() {
        return session;
    }

    void start() {
        if (started.compareAndSet(false, true)) {
            outputConsumer.start();
            eventConsumer.start();
        }
    }

    boolean isAlive() {
        return !closed.get() && client.isAlive();
    }

    private void consumeOutput() {
        long dropped = 0;
        try {
            while (!closed.get()) {
                Optional<PaneOutput> next = output.next();
                long nowDropped = output.droppedCount();
                if (nowDropped != dropped) {
                    dropped = nowDropped;
                    owner.outputDropped();
                }
                if (next.isEmpty()) {
                    return;
                }
                owner.output(next.orElseThrow().pane());
            }
        } catch (InterruptedException e) {
            if (!closed.get()) {
                Thread.currentThread().interrupt();
            }
        } finally {
            owner.ended(this);
        }
    }

    private void consumeEvents() {
        long dropped = 0;
        try {
            while (!closed.get()) {
                Optional<ControlEvent> next = events.next();
                long nowDropped = events.droppedCount();
                if (nowDropped != dropped) {
                    dropped = nowDropped;
                    owner.stateLost();
                }
                if (next.isEmpty()) {
                    return;
                }
                owner.stateChanged();
            }
        } catch (InterruptedException e) {
            if (!closed.get()) {
                Thread.currentThread().interrupt();
            }
        } finally {
            owner.ended(this);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        output.close();
        events.close();
        outputConsumer.interrupt();
        eventConsumer.interrupt();
        connection.changeClients(() -> {
            try {
                client.close();
            } finally {
                if (clientName != null) {
                    connection.reveal(clientName);
                }
            }
        });
        if (started.get()) {
            join(outputConsumer);
            join(eventConsumer);
        }
    }

    private static void join(Thread thread) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_MILLIS);
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
