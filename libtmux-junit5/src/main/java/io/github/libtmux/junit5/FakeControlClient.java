package io.github.libtmux.junit5;

import io.github.libtmux.transport.CommandResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A {@code tmux -C} client of a {@link FakeTmux}, as the process a control client talks to.
 *
 * <p>It reads one command line at a time, has the fake answer it, and writes the reply block tmux
 * would: {@code %begin}, the output, then {@code %end} or {@code %error}. Pushed output and
 * notifications are written between replies, never inside one, as tmux writes them. It ends, with
 * {@code %exit}, when the fake restarts or the client closes its input.
 */
final class FakeControlClient extends Process {

    private final Function<List<List<String>>, CommandResult> answer;
    private final OutputStream toClient;
    private final InputStream fromFake;
    private final OutputStream fromClient;
    private final InputStream toFake;
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicInteger commands = new AtomicInteger();
    private final Object writing = new Object();

    FakeControlClient(Function<List<List<String>>, CommandResult> answer) {
        this.answer = answer;
        Pipe out = open();
        Pipe in = open();
        this.toClient = Channels.newOutputStream(out.sink());
        this.fromFake = Channels.newInputStream(out.source());
        this.fromClient = Channels.newOutputStream(in.sink());
        this.toFake = Channels.newInputStream(in.source());
        // Attaching is itself a command, and its reply is what tells the client it is up.
        reply(new CommandResult(0, List.of(), List.of()));
        Thread reader = new Thread(this::serve, "fake-tmux-control");
        reader.setDaemon(true);
        reader.start();
    }

    /** Writes an unprompted line, such as {@code %output}, between replies. */
    void push(String line) {
        write(line + "\n");
    }

    /** Ends the client as a server that went away does. */
    void end() {
        write(word("exit") + "\n");
        finish();
    }

    private void serve() {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(toFake, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                reply(answer.apply(parse(line)));
            }
        } catch (IOException | UncheckedIOException ended) {
            // The client closed its end, or the fake did; either way this client is over.
        } finally {
            finish();
        }
    }

    /**
     * A command line as tmux's parser reads the ones this library writes: each argument single
     * quoted, a quote as {@code '\''}, a newline or return as {@code "\n"} or {@code "\r"}, and an
     * unquoted {@code ;} between commands.
     */
    static List<List<String>> parse(String line) {
        List<List<String>> commands = new ArrayList<>();
        List<String> argv = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\'') {
                int close = line.indexOf('\'', index + 1);
                word.append(line, index + 1, close < 0 ? line.length() : close);
                index = close < 0 ? line.length() : close;
                inWord = true;
            } else if (character == '"') {
                int close = line.indexOf('"', index + 1);
                String quoted = line.substring(index + 1, close < 0 ? line.length() : close);
                word.append(quoted.replace("\\n", "\n").replace("\\r", "\r"));
                index = close < 0 ? line.length() : close;
                inWord = true;
            } else if (character == '\\' && index + 1 < line.length()) {
                word.append(line.charAt(++index));
                inWord = true;
            } else if (character == ' ') {
                if (inWord) {
                    argv.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else if (character == ';' && !inWord) {
                if (!argv.isEmpty()) {
                    commands.add(List.copyOf(argv));
                    argv.clear();
                }
            } else {
                word.append(character);
                inWord = true;
            }
        }
        if (inWord) {
            argv.add(word.toString());
        }
        if (!argv.isEmpty()) {
            commands.add(List.copyOf(argv));
        }
        return commands;
    }

    private void reply(CommandResult result) {
        int number = commands.getAndIncrement();
        String guard = " 1790000000 " + number + " " + (number == 0 ? 0 : 1);
        StringBuilder block = new StringBuilder(word("begin")).append(guard).append('\n');
        List<String> lines = new ArrayList<>(result.stdout());
        if (!result.succeeded()) {
            lines.addAll(result.stderr());
        }
        lines.forEach(line -> block.append(line).append('\n'));
        block.append(word(result.succeeded() ? "end" : "error")).append(guard).append('\n');
        write(block.toString());
    }

    /** A control-mode word: tmux marks each with a leading percent sign. */
    private static String word(String name) {
        return '%' + name;
    }

    private void write(String text) {
        synchronized (writing) {
            if (ended.getCount() == 0) {
                return;
            }
            try {
                toClient.write(text.getBytes(StandardCharsets.UTF_8));
                toClient.flush();
            } catch (IOException gone) {
                finish();
            }
        }
    }

    private void finish() {
        synchronized (writing) {
            if (ended.getCount() == 0) {
                return;
            }
            ended.countDown();
            closeQuietly(toClient);
            closeQuietly(toFake);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already closed, which is the state being asked for.
        }
    }

    private static Pipe open() {
        try {
            return Pipe.open();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public OutputStream getOutputStream() {
        return fromClient;
    }

    @Override
    public InputStream getInputStream() {
        return fromFake;
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        ended.await();
        return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return ended.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (ended.getCount() != 0) {
            throw new IllegalThreadStateException("the fake control client is still attached");
        }
        return 0;
    }

    @Override
    public boolean isAlive() {
        return ended.getCount() != 0;
    }

    @Override
    public void destroy() {
        finish();
    }
}
