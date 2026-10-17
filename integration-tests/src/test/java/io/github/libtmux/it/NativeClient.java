package io.github.libtmux.it;

import io.github.libtmux.Client;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.internal.ProcessTree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Owns an ordinary terminal client for a local test server. */
final class NativeClient implements AutoCloseable {
    private static final String DRIVER = helper() + """
            import fcntl, pty, signal, sys, termios
            master, slave = pty.openpty()
            os.set_blocking(master, False)
            def terminal():
                os.setsid()
                fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
            signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
            child = None
            trace = bytearray()
            try:
                child = subprocess.Popen(sys.argv[1:], stdin=slave, stdout=slave,
                    stderr=slave, env=dict(os.environ, TERM='xterm'), preexec_fn=terminal)
                os.close(slave)
                slave = None
                while child.poll() is None:
                    chunk = drain_pty(master)
                    trace.extend(chunk[:max(0, 65536-len(trace))])
                    if 0 in select.select([0, master], [], [], .025)[0]:
                        data = os.read(0, 65536)
                        if not data: break
                        os.write(master, data)
            finally:
                if child is not None:
                    if child.poll() is None: child.terminate()
                    try: wait_pty(child, master, 2)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        wait_pty(child, master, 2)
                    if child.returncode not in (0, -signal.SIGTERM):
                        sys.stderr.buffer.write(trace)
                if slave is not None: os.close(slave)
                os.close(master)
            """;
    private final Server server;
    private final Process process;
    private final ProcessTree tree;
    private final Path log;
    private String name = "";

    private NativeClient(Server server, Process process, Path log) {
        this.server = server;
        this.process = process;
        this.tree = new ProcessTree(process);
        this.log = log;
    }

    static NativeClient attach(Session session) throws Exception {
        Server server = session.server();
        Set<String> before = server.clients().stream().map(Client::name).collect(Collectors.toSet());
        Path log = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")), "ordinary-client-", ".log");
        List<String> command = new ArrayList<>(List.of("python3", "-u", "-c", DRIVER));
        command.addAll(server.config().endpointCommand());
        command.addAll(List.of("attach-session", "-t", session.id().value()));
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("TMUX");
        builder.environment().remove("TMUX_PANE");
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(log);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        NativeClient client = new NativeClient(server, process, log);
        try {
            if (!Await.until(() -> !client.process.isAlive()
                            || server.clients().stream().anyMatch(seen -> !before.contains(seen.name())))
                    || !client.process.isAlive()) {
                throw new IOException("ordinary client did not attach: " + Files.readString(log));
            }
            client.name = server.clients().stream()
                    .map(Client::name)
                    .filter(seen -> !before.contains(seen))
                    .findFirst()
                    .orElseThrow(() -> new IOException("ordinary client disappeared after attaching"));
            return client;
        } catch (Exception | Error failure) {
            try {
                client.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    void run(String... argv) throws Exception {
        String marker = "@ordinary-client-" + UUID.randomUUID();
        String command = CommandStrings.group(List.of(List.of(argv), List.of("set-option", "-g", marker, "done")));
        try {
            var prompt = server.cmd("command-prompt", "-b", "-t", name);
            if (!prompt.succeeded()) throw new IOException("ordinary client prompt failed: " + prompt.stderr());
            process.getOutputStream().write((command + "\r").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            if (!Await.until(() -> server.globalOptions().get(marker).isPresent())) {
                throw new IOException("ordinary client did not complete its command: " + Files.readString(log));
            }
        } finally {
            server.globalOptions().unset(marker);
        }
    }

    @Override
    public void close() throws IOException {
        tree.captureDescendants();
        boolean reclaimed;
        try {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("ordinary client did not stop");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while closing ordinary client", interrupted);
        } finally {
            reclaimed = tree.terminate();
        }
        if (!reclaimed) throw new IOException("ordinary client process tree survived");
        if (process.exitValue() != 0) throw new IOException("ordinary client failed: " + Files.readString(log));
        Files.deleteIfExists(log);
    }

    private static String helper() {
        try (var source = NativeClient.class.getResourceAsStream("/io/github/libtmux/fixtures/pty.py")) {
            if (source == null) throw new IllegalStateException("missing PTY test helper");
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
