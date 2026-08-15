package io.github.libtmux.transport;

import io.github.libtmux.ExecutionMode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs each command on a virtual thread, and waits for it there.
 *
 * <p>The {@link ExecutionMode#VIRTUAL} carrier. It wraps another carrier rather than reaching tmux
 * itself, so the command travels exactly as it would have; what changes is which thread is parked
 * while tmux answers.
 *
 * <p><strong>What this buys, and what it does not.</strong> The caller still blocks: the work is
 * handed to a virtual thread and joined, so nothing becomes asynchronous. What moves is where the
 * parking happens. A caller running on a small pool of platform threads — a servlet container, a
 * fixed executor — keeps those threads free while tmux is slow, at the cost of one virtual thread
 * per command, which is cheap.
 *
 * <p>A caller already on a virtual thread gains nothing from this and should not select it. The
 * ordinary carriers are safe to call from one: they block on
 * {@link java.util.concurrent.locks.ReentrantLock} rather than inside {@code synchronized}, so a
 * blocked call releases its carrier. That is a property of the transports rather than a mode, and
 * {@code CarrierStarvationTest} keeps it true.
 */
public final class VirtualThreadTransport implements TmuxTransport {

    private final TmuxTransport delegate;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param delegate the carrier that actually reaches tmux, closed with this one
     */
    public VirtualThreadTransport(TmuxTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        if (closed.get()) {
            throw new IllegalStateException("transport is closed");
        }
        AtomicReference<CommandResult> answered = new AtomicReference<>();
        // Every Throwable, not only the ones a caller was expecting. An Error left behind would kill
        // the worker with nothing recorded, and the join would then return an answer of null.
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().name("libtmux-virtual").start(() -> {
            try {
                answered.set(delegate.execute(request));
            } catch (Throwable e) {
                failed.set(e);
            }
        });
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmuxTransportException("interrupted while waiting for tmux", DispatchOutcome.UNKNOWN, e);
        }
        // Rethrown as it was, so a caller sees the same failure and the same dispatch certainty it
        // would have seen without this carrier in the way. execute declares no checked exception, so
        // the first two arms are the whole of what a conforming delegate can throw.
        Throwable thrown = failed.get();
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new TmuxTransportException("tmux could not be run to completion", DispatchOutcome.UNKNOWN, thrown);
        }
        CommandResult result = answered.get();
        if (result == null) {
            // A delegate that answers null is not one this library wrote. Refused here rather than
            // handed on, because a null crossing into annotated code fails somewhere far less clear.
            throw new TmuxTransportException("the carrier beneath answered nothing", DispatchOutcome.UNKNOWN, null);
        }
        return result;
    }

    @Override
    public String realm() {
        return delegate.realm();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close();
        }
    }
}
