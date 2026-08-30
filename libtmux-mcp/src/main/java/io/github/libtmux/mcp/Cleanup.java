package io.github.libtmux.mcp;

import org.jspecify.annotations.Nullable;

/** Runs every cleanup while retaining the first failure as the primary one. */
final class Cleanup {

    private @Nullable Throwable failure;

    Cleanup() {}

    Cleanup(@Nullable Throwable failure) {
        this.failure = failure;
    }

    void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error next) {
            add(next);
        }
    }

    void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception | Error next) {
            add(next);
        }
    }

    @Nullable
    Throwable failure() {
        return failure;
    }

    void throwIfFailed() {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("cleanup failed", failure);
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private void add(Throwable next) {
        if (failure == null) {
            failure = next;
        } else if (failure != next) {
            failure.addSuppressed(next);
        }
    }
}
