package io.github.libtmux.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import io.github.libtmux.transport.CommandResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Lets the documentation suite run its snippets against a tmux server an outside supervisor lends,
 * on the same activation contract the example programs use ({@code LIBTMUX_ARENA_DESCRIPTOR} and
 * its companions), instead of a server this suite starts. Delegates every callback to
 * {@link TmuxExtension} unchanged when the contract is not activated.
 *
 * <p>A lent server is shared for the whole run, so a case gets a session of its own rather than a
 * server of its own: whatever a snippet leaves behind is reaped by session id once the case ends,
 * which is what a fresh server would have given the next case anyway, and the supervisor's own
 * session is never one of the ids reaped.
 *
 * <p>A case named in {@link #NEEDS_A_FRESH_SERVER} is the exception: its claim is about state a
 * shared server cannot promise, so it runs against a private server of its own instead, exactly as it
 * would with the arena inactive.
 */
final class DocumentationArena
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    static final String ARTIFACT = "java-docs-tests";

    private static final String HARNESS_SESSION = "libtmux-docs-tests";

    /** The one call a snippet must never make against a server this suite was only lent. */
    private static final String KILLS_THE_SERVER = "killServer(";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(DocumentationArena.class);
    private static final String CASE_KEY = "case";

    /** Every source a case actually ran a snippet against, so evidence names only what was audited. */
    private static final Set<Path> AUDITED = ConcurrentHashMap.newKeySet();

    private final TmuxExtension ordinary = new TmuxExtension();

    /**
     * Empty when the descriptor is absent, so ordinary command-line use is untouched. Mirrors the
     * activation contract {@code ArenaSupport} gives the example programs.
     */
    static Optional<ServerConfig> config(Map<String, String> environment) {
        String descriptor = environment.get("LIBTMUX_ARENA_DESCRIPTOR");
        if (descriptor == null || descriptor.isEmpty()) {
            return Optional.empty();
        }
        String reported = required(environment, "LIBTMUX_ARENA_ARTIFACT");
        if (!ARTIFACT.equals(reported)) {
            throw new IllegalArgumentException("LIBTMUX_ARENA_ARTIFACT does not select " + ARTIFACT);
        }
        return Optional.of(ServerConfig.builder()
                .binary(required(environment, "LIBTMUX_TMUX_BIN"))
                .endpoint(ServerEndpoint.socketPath(Path.of(required(environment, "LIBTMUX_SOCKET_PATH"))))
                .build());
    }

    static Optional<ServerConfig> config() {
        return config(System.getenv());
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required for arena mode");
        }
        return value;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // Resolved once up front: an incomplete contract must refuse before any case runs, not fail
        // once per snippet.
        config();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Optional<ServerConfig> arena = config();
        if (arena.isEmpty()) {
            ordinary.beforeEach(context);
            return;
        }
        Server client = Server.open(arena.orElseThrow());
        ownSession(client, HARNESS_SESSION);
        Set<String> baseline = sessionIds(client);
        context.getStore(NAMESPACE).put(CASE_KEY, new Case(client, baseline));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (config().isEmpty()) {
            ordinary.afterEach(context);
            return;
        }
        Case current = context.getStore(NAMESPACE).get(CASE_KEY, Case.class);
        if (current == null) {
            return;
        }
        try {
            for (String id : sessionIds(current.client())) {
                if (!current.baseline().contains(id)) {
                    current.client().cmd("kill-session", "-t", id);
                }
            }
        } finally {
            current.client().close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return ordinary.supportsParameter(parameter, context);
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Optional<ServerConfig> arena = config();
        if (arena.isEmpty()) {
            return ordinary.resolveParameter(parameter, context);
        }
        if (parameter.getParameter().getType() == Server.class) {
            return context.getStore(NAMESPACE).get(CASE_KEY, Case.class).client();
        }
        ServerEndpoint.SocketPath socket =
                (ServerEndpoint.SocketPath) arena.orElseThrow().endpoint();
        return new TmuxSocketPath(socket.path());
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (AUDITED.isEmpty()) {
            // Either the run never activated the arena, or an incomplete contract already refused
            // every case in beforeAll — either way there is nothing this run actually audited.
            return;
        }
        ServerConfig lent = config().orElseThrow();
        ServerEndpoint.SocketPath socket = (ServerEndpoint.SocketPath) lent.endpoint();
        ObjectMapper json = new ObjectMapper();
        try (Server client = Server.open(lent)) {
            String challenge = client.globalOptions()
                    .get("@libtmux_arena_challenge")
                    .filter(value -> !value.isEmpty())
                    .orElseThrow(() -> new IllegalStateException("arena challenge is missing"));
            long serverPid = Long.parseLong(client.expand("#{pid}"));
            String actualSocket = client.expand("#{socket_path}");
            if (!socket.path().toString().equals(actualSocket)) {
                throw new IllegalStateException("arena server socket does not match the requested socket");
            }
            for (Path source : new TreeSet<>(AUDITED)) {
                ObjectNode evidence = json.createObjectNode()
                        .put("artifact", ARTIFACT)
                        .put("challenge", challenge)
                        .put("schema", 1)
                        .put("server_pid", serverPid)
                        .put("socket_path", actualSocket)
                        .put("source", source.toString());
                System.out.println("LIBTMUX_ARENA_EVIDENCE=" + json.writeValueAsString(evidence));
            }
        }
    }

    /** The session {@code session}, {@code window} and {@code pane} bind to when the arena is active. */
    static Optional<Session> harnessSession(Server server, Optional<ServerConfig> arena) {
        return arena.isPresent() ? Optional.of(ownSession(server, HARNESS_SESSION)) : Optional.empty();
    }

    /** Records that a case for {@code source} ran, so evidence is emitted for what the run audited. */
    static void audited(Path source, Optional<ServerConfig> arena) {
        if (arena.isPresent()) {
            AUDITED.add(source);
        }
    }

    /**
     * Refuses a snippet that would stop a server the arena only lent, reporting it as skipped rather
     * than letting it run. A no-op when the arena is not active.
     */
    static void refuseIfStopsTheServer(Snippet snippet, Optional<ServerConfig> arena) {
        if (arena.isPresent() && snippet.code().contains(KILLS_THE_SERVER)) {
            Assumptions.assumeTrue(false, snippet.where() + " stops a server this suite does not own");
        }
    }

    /** Seeds or selects a session by name, so a case never operates on the supervisor's own session. */
    private static Session ownSession(Server server, String name) {
        return server.hasSession(name)
                ? server.sessions().stream()
                        .filter(candidate -> candidate.name().equals(name))
                        .findFirst()
                        .orElseThrow()
                : server.newSession(name);
    }

    private static Set<String> sessionIds(Server server) {
        return new HashSet<>(server.cmd("list-sessions", "-F", "#{session_id}").stdout());
    }

    /** One case's lent-server client and the session ids that existed before it ran. */
    private record Case(Server client, Set<String> baseline) {}

    /**
     * A snippet whose claim is about state only a server nothing else has touched can promise — its
     * own session's exact name, a count taken across the whole server, or the like — which a server
     * already holding the supervisor's session cannot give it.
     *
     * @param file the document the snippet came from, matching {@link Snippet#file()}
     * @param line the line its fence opened on, matching {@link Snippet#line()}
     * @param reason what it asserts that a shared server cannot promise
     */
    record Exemption(Path file, int line, String reason) {
        String where() {
            return file + ":" + line;
        }
    }

    /**
     * Snippets that need a fresh server's exact state rather than the arena's lent, shared one:
     * discovered once, by running the arena end to end and reading which cases failed for exactly
     * that reason. {@link DocumentationArenaTest} proves every entry still names a real snippet.
     */
    static final List<Exemption> NEEDS_A_FRESH_SERVER = List.of(
            new Exemption(Path.of("README.md"), 73, "asserts the exact session count"),
            new Exemption(Path.of("README.md"), 147, "asserts the exact session name"),
            new Exemption(Path.of("libtmux/README.md"), 88, "asserts the exact session name"),
            new Exemption(Path.of("libtmux-jackson/README.md"), 50, "asserts the exact pane count"),
            new Exemption(Path.of("libtmux-junit5/README.md"), 61, "asserts the exact session count and name"),
            new Exemption(Path.of("libtmux-junit5/README.md"), 69, "asserts the exact session count"),
            new Exemption(Path.of("libtmux-junit5/README.md"), 79, "asserts the exact socket root"),
            new Exemption(Path.of("libtmux-workspace/README.md"), 84, "asserts the exact session count and name"),
            new Exemption(Path.of("docs/guide/filtering.md"), 7, "asserts the exact window count"),
            new Exemption(Path.of("docs/guide/filtering.md"), 122, "asserts the exact pane count"),
            new Exemption(Path.of("docs/guide/getting-started.md"), 120, "asserts the exact window count"),
            new Exemption(
                    Path.of("docs/guide/options-and-hooks.md"), 21, "asserts a session with no options set on it yet"),
            new Exemption(
                    Path.of("docs/guide/snapshots-and-handles.md"),
                    63,
                    "asserts the exact session name and pane coordinates"),
            new Exemption(
                    Path.of("docs/guide/testing.md"), 22, "asserts the exact session count, name and socket root"));

    /** The listed reason for {@code snippet}, so a case can be told why it does not borrow. */
    static Optional<Exemption> exemptionFor(Snippet snippet) {
        return NEEDS_A_FRESH_SERVER.stream()
                .filter(exemption -> exemption.file().equals(snippet.file()) && exemption.line() == snippet.line())
                .findFirst();
    }

    /**
     * A private server for one exempted case, torn down when the case ends — the same fixture the
     * arena's absence would have given it, built by hand because {@link TmuxExtension}'s own fixture
     * is not exposed outside its package.
     */
    static final class FreshServer implements AutoCloseable {

        private static final Path ROOT = Path.of("/tmp/libtmux-java-test");
        private static final Duration KILL_TIMEOUT = Duration.ofSeconds(5);

        private final Path directory;
        private final Server server;
        private final TmuxSocketPath socket;

        private FreshServer(Path directory, Server server, TmuxSocketPath socket) {
            this.directory = directory;
            this.server = server;
            this.socket = socket;
        }

        static FreshServer start() {
            Path directory;
            try {
                Files.createDirectories(ROOT);
                directory = Files.createTempDirectory(ROOT, "exempt-");
                Files.writeString(directory.resolve("tmux.conf"), "");
            } catch (IOException e) {
                throw new UncheckedIOException("could not make a private directory for an exempted case", e);
            }
            Path endpoint = directory.resolve("s");
            Server server = Server.open(ServerConfig.builder()
                    .binary(System.getProperty("libtmux.tmux", "tmux"))
                    .endpoint(ServerEndpoint.socketPath(endpoint))
                    .configFile(directory.resolve("tmux.conf"))
                    .build());
            CommandResult created = server.cmd("new-session", "-d", "-s", "libtmux");
            if (!created.succeeded()) {
                server.close();
                throw new IllegalStateException("could not start an exempted case's own server: " + created.stderr());
            }
            return new FreshServer(directory, server, new TmuxSocketPath(endpoint));
        }

        Server server() {
            return server;
        }

        TmuxSocketPath socket() {
            return socket;
        }

        @Override
        public void close() {
            try {
                server.cmd(List.of("kill-server"), KILL_TIMEOUT);
            } catch (RuntimeException e) {
                // A server that already exited is the outcome teardown wanted.
            }
            server.close();
            deleteTree(directory);
        }

        private static void deleteTree(Path root) {
            if (!Files.exists(root)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Best effort; a temporary directory is the operating system's to reclaim.
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("could not remove an exempted case's directory", e);
            }
        }
    }
}
