package io.github.libtmux.junit5;

import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.ServerLaunch;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Gives each test its own tmux server and checks its exit during teardown.
 *
 * <p>Each server comes from {@link Server#start(ServerLaunch)}, which owns its directory and its
 * exit. A shutdown hook also cleans up on JVM termination. A hard kill skips it; the library's next
 * start under the same root reaps servers whose owning JVM is gone.
 *
 * <p>All state lives in the extension store, never in fields. JUnit reuses one declaratively
 * registered extension instance for every test in a class, so a field is shared state the moment
 * tests run in parallel, and one test's teardown then reaches another test's server.
 *
 * <p>The server is started eagerly, before the test body, and the aggregate that owns it is
 * registered before any process exists. Registering afterwards leaves a window in which a failure
 * partway through setup leaks a running tmux that nothing is left holding.
 *
 * <p>Teardown is driven from a lifecycle callback and the aggregate is also {@link AutoCloseable}
 * for the framework's own store handling. Relying on store auto-close alone is a silent dependency
 * on a configuration property: with it disabled that design releases nothing and reports nothing.
 * The aggregate closes exactly once, so either path alone is sufficient.
 */
public final class TmuxExtension implements ParameterResolver, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TmuxExtension.class);
    private static final String KEY = "fixture";

    private static final Path FIXTURE_ROOT = Path.of("/tmp/libtmux-java-test");

    /** Every fixture this JVM currently holds a server for, so the shutdown hook knows what to end. */
    private static final Set<Fixture> LIVE = ConcurrentHashMap.newKeySet();

    static {
        // Covers the exits a lifecycle callback does not: a cancelled build, a SIGTERM, a
        // System.exit from something else in the JVM. The hook runs on termination and normal exit,
        // and does not run on SIGKILL — which is what the sweep is for. See
        // docs/decisions/0006-real-tmux-junit5-fixture-lifecycle.md.
        Runtime.getRuntime().addShutdownHook(new Thread(TmuxExtension::releaseAll, "libtmux-fixture-shutdown"));
    }

    private static void releaseAll() {
        for (Fixture fixture : LIVE) {
            try {
                fixture.close();
            } catch (RuntimeException e) {
                // Shutdown is not a place to report; the sweep is the backstop for whatever survives.
            }
        }
    }

    static Path fixtureRoot() {
        return FIXTURE_ROOT;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        fixture(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        // Never claims a bare Path: another extension is entitled to resolve those.
        return type == Server.class || type == TmuxSocketPath.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Fixture fixture = fixture(context);
        return parameter.getParameter().getType() == Server.class
                ? fixture.server()
                : new TmuxSocketPath(fixture.socket());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Fixture fixture = context.getStore(NAMESPACE).get(KEY, Fixture.class);
        if (fixture != null) {
            fixture.close();
        }
    }

    private static Fixture fixture(ExtensionContext context) {
        // The aggregate is stored while it still owns nothing, so a failure during startup is still
        // reachable by teardown.
        Fixture fixture = context.getStore(NAMESPACE).getOrComputeIfAbsent(KEY, key -> new Fixture(), Fixture.class);
        fixture.start();
        return fixture;
    }

    /** One test's server, started and ended by the library, released exactly once. */
    static final class Fixture implements AutoCloseable {

        private @Nullable Server server;
        private boolean started;
        private boolean closed;

        synchronized void start() {
            if (started) {
                return;
            }
            started = true;
            server = Server.start(ServerLaunch.builder()
                    // Lets one suite run against a matrix of tmux builds rather than whichever
                    // one happens to be on PATH.
                    .binary(System.getProperty("libtmux.tmux", "tmux"))
                    .root(FIXTURE_ROOT)
                    .build());
            LIVE.add(this);
            server().newSession("libtmux");
        }

        Server server() {
            Server current = server;
            if (current == null) {
                throw new IllegalStateException("the fixture server was never started");
            }
            return current;
        }

        Path socket() {
            return ((ServerEndpoint.SocketPath) server().config().endpoint()).path();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            LIVE.remove(this);
            Server current = server;
            if (current != null) {
                // Throws when the server still answers, leaving its directory in place.
                current.close();
            }
        }
    }
}
