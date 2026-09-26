package io.github.libtmux.workspace.cli;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

final class BorrowedOutput implements AutoCloseable {
    private final long ownerThreadId = Thread.currentThread().threadId();
    private final AtomicLong cancellationDeadline = new AtomicLong();
    private final Channel output;
    private final Channel error;

    BorrowedOutput(OutputStream output, OutputStream error) {
        this.output = new Channel(output);
        this.error = new Channel(error);
    }

    OutputStream output() {
        return output;
    }

    OutputStream error() {
        return error;
    }

    boolean interrupted() {
        return cancellationDeadline.get() != 0;
    }

    private void cancel() {
        cancellationDeadline.compareAndSet(0, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
    }

    @Override
    public void close() {
        output.close();
        error.close();
    }

    private final class Channel extends OutputStream {
        private final OutputStream borrowed;
        private final ExecutorService writer = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("workspace-output-", 0).factory());

        Channel(OutputStream borrowed) {
            this.borrowed = borrowed;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            byte[] copy = Arrays.copyOfRange(bytes, offset, offset + length);
            await(() -> {
                borrowed.write(copy);
                check();
                return null;
            });
        }

        @Override
        public void flush() throws IOException {
            await(() -> {
                borrowed.flush();
                check();
                return null;
            });
        }

        private void check() throws IOException {
            if (borrowed instanceof PrintStream stream && stream.checkError())
                throw new IOException("output stream failed");
        }

        private void await(Callable<Void> action) throws IOException {
            boolean restoreInterrupt = Thread.interrupted();
            if (restoreInterrupt) cancel();
            Future<Void> pending = null;
            try {
                long deadline = cancellationDeadline.get();
                if (deadline != 0
                        && (Thread.currentThread().threadId() != ownerThreadId || System.nanoTime() >= deadline))
                    throw new InterruptedIOException("output interrupted");
                pending = writer.submit(action);
                if (deadline == 0) pending.get();
                else pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                cancel();
                restoreInterrupt = true;
                if (pending != null) pending.cancel(true);
                throw new InterruptedIOException("output interrupted");
            } catch (TimeoutException timeout) {
                if (pending != null) pending.cancel(true);
                throw new InterruptedIOException("output interrupted");
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IOException("output failed", cause);
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            for (Runnable queued : writer.shutdownNow()) {
                if (queued instanceof Future<?> future) future.cancel(true);
            }
        }
    }
}
