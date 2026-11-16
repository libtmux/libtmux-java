package io.github.libtmux.it;

import io.github.libtmux.Client;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.internal.ProcessTree;
import java.io.IOException;
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
    /** The Python program that gives the client a terminal; a checked file under the test resources. */
    private static final String DRIVER = "/io/github/libtmux/it/ordinary_client.py";

    /** The module the driver imports, which the build copies from {@code scripts/pty-helper}. */
    private static final String HELPER = "/io/github/libtmux/fixtures/pty_helper.py";

    private final Server server;
    private final Process process;
    private final ProcessTree tree;
    private final Path programs;
    private final Path log;
    private String name = "";

    private NativeClient(Server server, Process process, Path programs, Path log) {
        this.server = server;
        this.process = process;
        this.tree = new ProcessTree(process);
        this.programs = programs;
        this.log = log;
    }

    static NativeClient attach(Session session) throws Exception {
        Server server = session.server();
        Set<String> before = server.clients().stream().map(Client::name).collect(Collectors.toSet());
        Path programs = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "ordinary-client-");
        Path log = programs.resolve("client.log");
        Process process;
        try {
            Path driver = copyResource(DRIVER, programs);
            copyResource(HELPER, programs);
            List<String> command = new ArrayList<>(List.of("python3", "-u", driver.toString()));
            command.addAll(server.config().endpointCommand());
            command.addAll(List.of("attach-session", "-t", session.id().value()));
            ProcessBuilder builder =
                    new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().remove("TMUX");
            builder.environment().remove("TMUX_PANE");
            // The helper is imported, so Python would otherwise leave a __pycache__ in the directory.
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            process = builder.start();
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTree(programs);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        NativeClient client = new NativeClient(server, process, programs, log);
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
        deleteTree(programs);
    }

    /**
     * Copies a Python program from the test resources into {@code directory}: Python puts a script's own
     * directory on its import path, so the driver finds {@code pty_helper} beside it.
     */
    private static Path copyResource(String resource, Path directory) throws IOException {
        try (var source = NativeClient.class.getResourceAsStream(resource)) {
            if (source == null) throw new IllegalStateException("missing test resource " + resource);
            Path target = directory.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            Files.copy(source, target);
            return target;
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) Files.deleteIfExists(entry);
        }
        Files.deleteIfExists(directory);
    }
}
