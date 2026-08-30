package io.github.libtmux.internal;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns bounded, idempotent termination of one process and its observable descendants. */
public final class ProcessTree {

    private static final Duration GRACEFUL_WAIT = Duration.ofMillis(250);
    private static final Duration FORCIBLE_WAIT = Duration.ofSeconds(5);

    private final Process root;
    private final Set<ProcessHandle> descendants = new LinkedHashSet<>();
    private boolean captured;
    private boolean captureFailed;
    private boolean terminated;
    private boolean reclaimed;

    /** Takes ownership of terminating {@code root} and descendants visible from it. */
    public ProcessTree(Process root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Retains descendants before another cleanup step can make the root disappear. */
    public synchronized void captureDescendants() {
        if (!captured && !terminated) {
            rememberDescendants();
            captured = true;
        }
    }

    /** Terminates the known process tree, closes the root's streams, and reports reclamation. */
    public synchronized boolean terminate() {
        if (terminated) {
            return reclaimed;
        }
        AtomicBoolean interrupted = new AtomicBoolean(Thread.interrupted());
        try {
            if (!captured) {
                rememberDescendants();
                captured = true;
            }
            stopProcesses(interrupted);
            boolean streamsClosed = closeStreams();
            reclaimed = !captureFailed
                    && streamsClosed
                    && !isAlive(root)
                    && descendants.stream().noneMatch(ProcessTree::isAlive);
        } catch (RuntimeException e) {
            reclaimed = false;
        } finally {
            terminated = true;
            if (interrupted.get()) {
                Thread.currentThread().interrupt();
            }
        }
        return reclaimed;
    }

    private void stopProcesses(AtomicBoolean interrupted) {
        signal(descendants, false);
        signalRoot(false);
        if (!awaitExit(this::allExited, GRACEFUL_WAIT, interrupted)) {
            signal(descendants, true);
            signalRoot(true);
            awaitExit(this::allExited, FORCIBLE_WAIT, interrupted);
        }
    }

    private boolean allExited() {
        return !isAlive(root) && descendants.stream().noneMatch(ProcessTree::isAlive);
    }

    private static boolean awaitExit(BooleanSupplier exited, Duration timeout, AtomicBoolean interrupted) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!exited.getAsBoolean()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(10, TimeUnit.NANOSECONDS.toMillis(left))));
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }
        return true;
    }

    private void rememberDescendants() {
        try {
            root.descendants().forEach(descendants::add);
        } catch (UnsupportedOperationException | SecurityException e) {
            // The root remains reclaimable when the platform cannot expose descendants.
        } catch (RuntimeException e) {
            captureFailed = true;
        }
    }

    private static void signal(Iterable<ProcessHandle> descendants, boolean forcibly) {
        List<ProcessHandle> ordered = new ArrayList<>();
        descendants.forEach(ordered::add);
        for (int index = ordered.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = ordered.get(index);
            if (!isAlive(descendant)) {
                continue;
            }
            try {
                if (forcibly) {
                    descendant.destroyForcibly();
                } else {
                    descendant.destroy();
                }
            } catch (RuntimeException ignored) {
                // The bounded wait and final live check decide whether reclamation succeeded.
            }
        }
    }

    private void signalRoot(boolean forcibly) {
        if (!isAlive(root)) {
            return;
        }
        try {
            if (forcibly) {
                root.destroyForcibly();
            } else {
                root.destroy();
            }
        } catch (RuntimeException ignored) {
            // The bounded wait and final live check decide whether reclamation succeeded.
        }
    }

    private boolean closeStreams() {
        boolean closed = close(root::getOutputStream);
        closed &= close(root::getInputStream);
        closed &= close(root::getErrorStream);
        return closed;
    }

    private static boolean close(Supplier<? extends Closeable> stream) {
        try {
            stream.get().close();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isAlive(Process process) {
        try {
            return process.isAlive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean isAlive(ProcessHandle process) {
        try {
            return process.isAlive();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
