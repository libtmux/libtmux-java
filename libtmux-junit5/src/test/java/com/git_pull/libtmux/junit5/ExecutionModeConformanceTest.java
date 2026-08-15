package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.git_pull.libtmux.ExecutionMode;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.ServerEndpoint;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.Window_;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same work, carried every way, answering the same thing.
 *
 * <p>A mode changes how a command travels and nothing else. That is the whole promise of
 * {@link ExecutionMode}, and it is the kind of promise that quietly stops being true, so every
 * scenario runs against one server per carrier and the answers are compared step by step.
 *
 * <p>Driven off {@code values()}, so a carrier added later is covered without anyone remembering to
 * add it here.
 *
 * <p>Servers are built here rather than taken from the fixture: the fixture provides one, and this
 * needs one per carrier differing in nothing else.
 */
final class ExecutionModeConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** One named step, and what it answered. */
    private record Step(String name, Function<Server, Object> work) {}

    private static final List<Step> SCENARIOS = List.of(
            new Step("the hierarchy a capture sees", server -> {
                Session session = server.sessions().get(0);
                session.newWindow(w -> w.named("editor").detached());
                session.newWindow(w -> w.named("logs").detached());
                List<Window> windows = session.refresh().windows();
                // Not every name: automatic-rename takes the first window's from what its pane runs,
                // and attaching a control client changes that. tmux naming a window after its process
                // is not a carrier changing an answer.
                return List.of(
                        Integer.toString(windows.size()),
                        windows.stream()
                                .map(Window::name)
                                .filter(name -> name.equals("editor") || name.equals("logs"))
                                .sorted()
                                .toList()
                                .toString());
            }),
            new Step("what creating a pane reported", server -> {
                Window window = server.sessions().get(0).windows().get(0);
                Pane created = window.split(s -> s.toRight());
                return List.of(
                        Integer.toString(created.window().panes().size()),
                        Boolean.toString(created.edges().right()));
            }),
            new Step("an option read back", server -> {
                var options = server.sessions().get(0).options();
                options.set("status-left", "carried");
                options.append("status-left", "-and-appended");
                return options.get("status-left").orElse("<absent>");
            }),
            new Step(
                    "a typed filter",
                    server -> server.windows().stream()
                            .filter(Window_.name().startsWith("edit"))
                            .map(Window::name)
                            .sorted()
                            .toList()),
            new Step("a format expansion", server -> {
                Session session = server.sessions().get(0);
                return session.expand("#{session_name}") + "|"
                        + session.windows().get(0).expand("#{window_index}");
            }),
            new Step("the outcomes of a batch", server -> {
                var result = server.batch()
                        .add("new-window", "-d", "-n", "one")
                        .add("new-window", "-d", "-n", "two")
                        .run();
                List<String> outcomes = new ArrayList<>();
                outcomes.add(Boolean.toString(result.succeeded()));
                result.operations()
                        .forEach(operation -> outcomes.add(operation.outcome().name()));
                return outcomes;
            }),
            new Step(
                    "how a failing command failed",
                    server -> Boolean.toString(
                            server.cmd("kill-session", "-t", "=no-such-session").succeeded())),
            // tmux runs the guarded command itself, and in control mode says so in the reply stream.
            // A carrier that took those lines for an answer would misread every reply after them, so
            // this step is here to read the hierarchy back afterwards and see whether it still parses.
            new Step("a capture taken after tmux ran a command of its own", server -> {
                server.ifShell("true", "rename-window guarded");
                Session session = server.sessions().get(0);
                for (int attempt = 0; attempt < 100 && !named(session, "guarded"); attempt++) {
                    sleepBriefly();
                }
                return session.refresh().windows().stream()
                        .map(Window::name)
                        .filter("guarded"::equals)
                        .toList()
                        .toString();
            }),
            // tmux ends a command at a trailing semicolon on any argument, not only at one standing
            // alone, so this argv is two commands however it travels. A carrier that quoted the
            // semicolon instead would make it one — the window would be named "grouped;" and would
            // take the listing as the program to run in it.
            new Step("an argv whose trailing semicolon ends a command", server -> {
                server.cmd(List.of("new-window", "-d", "-n", "grouped;", "list-windows", "-F", "#{window_name}"));
                Session session = server.sessions().get(0);
                return session.refresh().windows().stream()
                        .map(Window::name)
                        .filter(name -> name.startsWith("grouped"))
                        .sorted()
                        .toList()
                        .toString();
            }),
            // The other half of the same rule: a backslash before that semicolon keeps it, and the
            // argument ends with a semicolon rather than the command ending there.
            new Step("an argv whose trailing semicolon is escaped", server -> server.expand("escaped\\;")));

    private static boolean named(Session session, String name) {
        return session.refresh().windows().stream().anyMatch(window -> name.equals(window.name()));
    }

    /** if-shell runs its command asynchronously, so the effect is waited for rather than assumed. */
    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for if-shell", e);
        }
    }

    /**
     * Every scenario, in order, against one server per carrier, compared step by step.
     *
     * <p>One trajectory per carrier rather than a fresh server for every scenario: it starts one
     * tmux per mode instead of one per scenario per mode, and it compares a longer history rather
     * than a series of first moves, which is the harder thing to keep identical.
     */
    @Test
    void everyCarrierAnswersTheSame(@TempDir Path directory) throws Exception {
        Map<ExecutionMode, List<Object>> byMode = new LinkedHashMap<>();
        for (ExecutionMode mode : ExecutionMode.values()) {
            byMode.put(mode, trajectory(directory, mode));
        }

        // DIRECT is the baseline by name rather than by position: it is the default, and a reader
        // comparing against "whichever came first" would be reading the enum's declaration order.
        List<Object> expected = Objects.requireNonNull(byMode.get(ExecutionMode.DIRECT), "no trajectory for DIRECT");
        byMode.forEach((mode, answered) -> {
            for (int step = 0; step < SCENARIOS.size(); step++) {
                assertEquals(
                        expected.get(step),
                        answered.get(step),
                        SCENARIOS.get(step).name() + " differed between DIRECT and " + mode);
            }
        });
    }

    /**
     * A command tmux answers by running another one must not cost the carrier its place.
     *
     * <p>tmux reports the work it does itself in a control-mode reply stream. A carrier that took
     * those blocks for answers would read every later reply one out of step — an empty listing
     * returned as though it were a full one, which surfaces as a capture whose windows belong to a
     * session that listing never mentioned. Silent, and nothing about it looks like a transport
     * fault, so it is asserted directly rather than left to a scenario that happens to notice.
     */
    @Test
    void aCommandThatMakesTmuxRunAnotherLeavesTheCarrierInPlace(@TempDir Path directory) throws Exception {
        Path home = directory.resolve("deferred");
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        ServerConfig built = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .mode(ExecutionMode.CONTROL)
                .build();

        try (Server server = Server.open(built)) {
            try {
                server.newSession("deferred");
                server.ifShell("true", "rename-window then-ran");

                // Reading repeatedly rather than once: a carrier one reply out of step answers the
                // first read with the block before it, and only a later read runs out of stale ones.
                for (int read = 0; read < 20; read++) {
                    assertEquals(1, server.sessions().size(), "read " + read + " saw a different server");
                }
            } finally {
                server.killServer();
            }
        }
    }

    /** Runs every scenario in order against one server, and answers with what each one said. */
    private static List<Object> trajectory(Path root, ExecutionMode mode) throws IOException {
        Path home = root.resolve(mode.name().toLowerCase(Locale.ROOT));
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        ServerConfig built = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .mode(mode)
                .build();

        List<Object> answers = new ArrayList<>();
        try (Server server = Server.open(built)) {
            try {
                server.newSession("conformance");
                for (Step scenario : SCENARIOS) {
                    answers.add(scenario.work().apply(server));
                }
            } finally {
                server.killServer();
            }
        }
        return answers;
    }

    // ---------------------------------------------------------------------------- precedence

    /**
     * Highest first: the call, then the config.
     *
     * <p>Asserted by what each level answers rather than by reading a field back, because a carrier
     * that reported its own choice without using it would pass a weaker test.
     *
     * <p>The levels below a config — the property, the variable, and the default — are settled
     * before a server exists and are asserted in {@code ServerConfigTest}, which can hold the
     * ambient ones still while it looks.
     */
    @Test
    void aCallOverridesTheServerWhichOverridesTheDefault(@TempDir Path directory) throws Exception {
        Path home = directory.resolve("precedence");
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        ServerConfig control = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .mode(ExecutionMode.CONTROL)
                .build();

        assertEquals(ExecutionMode.CONTROL, control.mode(), "the config overrides the default");

        try (Server server = Server.open(control)) {
            server.newSession("precedence");

            // Whichever carrier answers, the answer is the same; the override changes only how.
            String byConfig = server.cmd(List.of("display-message", "-p", "#{session_name}"), TIMEOUT)
                    .stdout()
                    .get(0);
            String byCall = server.cmd(
                            List.of("display-message", "-p", "#{session_name}"), TIMEOUT, ExecutionMode.DIRECT)
                    .stdout()
                    .get(0);

            assertEquals(byConfig, byCall, "an override must not change what a command answers");
            assertEquals("precedence", byCall);
            server.killServer();
        }
    }

    /** A carrier made for an override belongs to the server that made it. */
    @Test
    void anOverrideCarrierIsClosedWithTheServer(@TempDir Path directory) throws Exception {
        Path home = directory.resolve("owning");
        Files.createDirectories(home);
        Path config = home.resolve("empty.conf");
        Files.writeString(config, "");
        ServerConfig built = ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(ServerEndpoint.socketPath(home.resolve("s")))
                .configFile(config)
                .mode(ExecutionMode.CONTROL)
                .build();

        Server server = Server.open(built);
        server.newSession("owning");
        server.cmd(List.of("display-message", "-p", "ok"), TIMEOUT, ExecutionMode.DIRECT);
        server.killServer();
        server.close();

        assertThrows(
                IllegalStateException.class,
                () -> server.cmd(List.of("display-message", "-p", "ok"), TIMEOUT, ExecutionMode.DIRECT),
                "a closed server must not carry anything, by any mode");
    }
}
