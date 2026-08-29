package io.github.libtmux;

import java.time.Duration;
import java.util.Objects;

/**
 * One of tmux's wait-for channels, named once.
 *
 * <p>A channel is server-wide and shared by everything on it, including programs running in panes.
 * Nothing creates or destroys one: a name either has a signal remembered against it or does not.
 *
 * <p>tmux's own {@code wait-for} has two traps, and this exists to close both. It exits successfully
 * when the server dies under the waiter, which is indistinguishable from a real signal, so the
 * server is checked afterwards rather than believed. And a signal sent when nobody is waiting is
 * remembered, satisfying the next wait whenever that happens — possibly in a later run of a
 * different program — so {@link #drain()} is how a caller starts from a known state on a channel
 * whose history is not its own.
 */
public final class Channel {

    /** Long enough for a pending signal to come straight back, short enough not to be a wait. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofMillis(250);

    private final Server server;
    private final String name;

    Channel(Server server, String name) {
        this.server = server;
        this.name = Objects.requireNonNull(name, "name");
    }

    /** The name this channel is known by on its server. */
    public String name() {
        return name;
    }

    /** Signals the channel, waking one waiter, or being remembered until something waits. */
    public void signal() {
        server.run(java.util.List.of("wait-for", "-S", name));
    }

    /**
     * Waits for something to signal the channel.
     *
     * @param timeout how long to wait
     * @return why the wait ended, which is never simply "successfully"
     */
    public WakeReason await(Duration timeout) {
        return server.awaitChannel(name, timeout, false);
    }

    /**
     * Waits while preserving process capacity for a call through this server that signals it.
     *
     * <p>Use this when the waiter and its release share a bounded transport. A wait released outside
     * that transport should use {@link #await}; reserving capacity for it only rejects useful
     * concurrency.
     *
     * @throws io.github.libtmux.transport.TmuxTransportException if the wait could not be dispatched
     */
    public WakeReason awaitReservingCapacity(Duration timeout) {
        return server.awaitChannel(name, timeout, true);
    }

    /**
     * Consumes a signal already waiting, so a stale one cannot satisfy a later wait.
     *
     * @return whether a signal was there to consume
     */
    public boolean drain() {
        return await(DRAIN_TIMEOUT) == WakeReason.SIGNALLED;
    }

    @Override
    public String toString() {
        return "Channel[" + name + "]";
    }
}
