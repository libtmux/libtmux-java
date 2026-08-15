package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.ExecutionMode;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Pane_;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.ServerEndpoint;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.SplitSpec;
import com.git_pull.libtmux.TmuxVersion;
import com.git_pull.libtmux.UnsupportedTmuxVersion;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.Window_;
import com.git_pull.libtmux.batch.BatchResult;
import com.git_pull.libtmux.control.ControlClient;
import com.git_pull.libtmux.control.PaneOutput;
import com.git_pull.libtmux.query.Selections;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The examples the documentation shows, executed.
 *
 * <p>Documentation that is not run rots silently, and a wrong example is worse than none: a reader
 * cannot tell it from a working one. Every snippet in the README and the guides appears here, so an
 * API change that would invalidate a snippet fails the build instead.
 */
@ExtendWith(TmuxExtension.class)
final class ExamplesTest {

    /** README: opening a server on your own socket and doing something with it. */
    @Test
    void quickstart(@TempDir Path directory) throws Exception {
        Path socket = directory.resolve("s");

        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .build();

        try (Server server = Server.open(config)) {
            Session session = server.newSession("demo");
            Window window = session.newWindow("build");
            Pane pane = window.split();

            pane.sendLine("echo hello from libtmux");

            assertTrue(awaitOutput(pane, "hello from libtmux"));
            assertEquals("demo", session.name());
            server.killServer();
        }
    }

    /** Guide: choosing how commands reach tmux, and the fallback until a session exists. */
    @Test
    void choosingAnExecutionMode(@TempDir Path directory) throws Exception {
        Path socket = directory.resolve("s");

        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .mode(ExecutionMode.CONTROL)
                .build();

        try (Server server = Server.open(config)) {
            Session first = server.newSession("work");
            first.newWindow(w -> w.named("logs"));

            assertEquals("work", first.name());
            assertTrue(
                    first.refresh().windows().stream().anyMatch(window -> "logs".equals(window.name())),
                    "the window made under the control carrier is not there");
            server.killServer();
        }
    }

    /** Guide: the carrier that waits on a virtual thread rather than on the caller's own. */
    @Test
    void waitingOnAVirtualThread(@TempDir Path directory) throws Exception {
        Path socket = directory.resolve("s");

        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .mode(ExecutionMode.VIRTUAL)
                .build();

        try (Server server = Server.open(config)) {
            Session session = server.newSession("work");

            assertEquals("work", session.name(), "a carrier changes the waiting and not the answer");
            server.killServer();
        }
    }

    /** Guide: sessions, windows and panes are described the same way. */
    @Test
    void describingWhatYouCreate(Server server) throws Exception {
        Session build = server.newSession(s -> s.named("build").firstWindowNamed("editor"));
        Window logs = build.newWindow(w -> w.named("logs").running("sleep", "30"));

        assertEquals("editor", build.windows().get(0).name());
        assertEquals("logs", logs.name());
        assertTrue(
                await(() ->
                        "sleep".equals(logs.activePane().orElseThrow().refresh().currentCommand())),
                "the window ran what it was given");
    }

    /** Guide: describing a split, and reusing the description. */
    @Test
    void describingASplit(Server server, @TempDir Path directory) throws Exception {
        Window window = server.sessions().get(0).windows().get(0);
        Pane pane = window.activePane().orElseThrow();

        Pane side = pane.split(s -> s.toRight().percent(30));
        Pane app = pane.split(s -> s.running("sleep", "30").in(directory));

        assertTrue(side.edges().right());
        assertTrue(await(() -> "sleep".equals(app.refresh().currentCommand())));

        Session session = server.sessions().get(0);
        SplitSpec sidebar = SplitSpec.builder().toRight().percent(25).build();

        Pane leftSide = session.newWindow("left").split(sidebar);
        Pane rightSide = session.newWindow("right").split(sidebar);

        assertTrue(leftSide.edges().right(), "one description, applied in its own window");
        assertTrue(rightSide.edges().right(), "and again in another");

        if (!server.version().atLeast(new TmuxVersion(3, 7, ""))) {
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.split(s -> s.empty()));
        }
    }

    /** Guide (options-and-hooks): narrow and wide views, and writing without replacing. */
    @Test
    void optionsNarrowAndWide(Server server) {
        Session session = server.sessions().get(0);
        var options = session.options();

        assertTrue(options.all().isEmpty());
        assertFalse(options.effective().isEmpty());

        options.set("status-left", "one");
        options.append("status-left", "-two");
        assertEquals(java.util.Optional.of("one-two"), options.get("status-left"));

        assertFalse(options.setIfAbsent("status-left", "three"));
        options.setExpanded("status-left", "in #{session_name}");
        assertEquals(java.util.Optional.of("in " + session.name()), options.get("status-left"));
    }

    /** Guide (options-and-hooks): a hook is an array, and it lives at one scope. */
    @Test
    void hooksAreArraysAtAScope(Server server) {
        Session session = server.sessions().get(0);
        Window window = session.windows().get(0);

        session.hooks().set("after-new-window", "display-message one");
        session.hooks().append("after-new-window", "display-message two");
        assertEquals(
                List.of("display-message one", "display-message two"),
                session.hooks().all().get("after-new-window"));

        window.hooks().set("pane-focus-in", "display-message belongs-here");
        window.hooks().set("alert-bell", "display-message does-not");

        assertTrue(window.hooks().all().containsKey("pane-focus-in"));
        assertFalse(window.hooks().all().containsKey("alert-bell"), "a session hook set at window scope is dropped");
    }

    /** Guide (snapshots-and-handles): renaming does not make a different session. */
    @Test
    void identityIsWhatAUserCannotChange(Server server) {
        Session before = server.sessions().get(0);

        Session renamed = before.rename("something-else");

        assertEquals(before, renamed);
    }

    /** Guide (snapshots-and-handles): expand reaches past what a snapshot carries. */
    @Test
    void expandingReachesPastTheSnapshot(Server server) {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        String where = pane.expand("#{session_name}:#{window_index}.#{pane_index}");
        String version = server.expand("#{version}");

        assertTrue(where.contains(":"), where);
        assertEquals(server.version().toString(), version);
    }

    /** Guide (snapshots-and-handles): reading past the visible area. */
    @Test
    void readingTheScrollback(Server server) {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        List<String> everything = pane.capture(c -> c.fromStartOfHistory());
        List<String> recent = pane.capture(c -> c.from(-10));

        assertTrue(everything.size() >= recent.size() || !everything.isEmpty());
    }

    /** Guide: a capture answers questions without asking tmux anything. */
    @Test
    void traversingACapture(Server server) {
        server.sessions().get(0).newWindow("editor");

        for (Session session : server.sessions()) {
            for (Window window : session.windows()) {
                for (Pane pane : window.panes()) {
                    assertEquals(window.id(), pane.window().id());
                }
            }
        }

        assertEquals(2, server.sessions().get(0).windows().size());
    }

    /** Guide: filtering with an expression rather than a lambda. */
    @Test
    void filtering(Server server) {
        server.sessions().get(0).newWindow("editor");
        server.sessions().get(0).newWindow("logs");

        List<Window> editors = server.windows().stream()
                .filter(Window_.name().startsWith("edit"))
                .toList();

        Window only = Selections.exactlyOne(editors);

        assertEquals("editor", only.name());
    }

    /** Guide: an expression can be read as well as run. */
    @Test
    void anExpressionDescribesItself(Server server) {
        var busy = Window_.panes().any(Pane_.command().startsWith("nv"));

        assertTrue(busy.describe().contains("pane_current_command"), busy.describe());
        assertEquals(0, server.windows().stream().filter(busy).count());
    }

    /** Guide: several commands in one invocation, each with its own outcome. */
    @Test
    void batching(Server server) {
        BatchResult result = server.batch()
                .add("new-window", "-d", "-n", "one")
                .add("new-window", "-d", "-n", "two")
                .run();

        assertTrue(result.succeeded());
        assertEquals(2, result.operations().size());
    }

    /** Guide: chaining, where each step acts on what the last one made. */
    @Test
    void chaining(Server server) {
        server.chain()
                .newWindow("built")
                .splitLeftRight()
                .sendLine("echo chained")
                .run();

        assertEquals(2, server.sessions().get(0).windows().size());
    }

    /** Guide: watching a session's output as it happens. */
    @Test
    void streaming(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
            List<PaneOutput> seen = new CopyOnWriteArrayList<>();
            client.onOutput(seen::add);

            client.send("send-keys", "-t", session.name(), "echo streamed", "Enter");

            assertTrue(
                    await(() -> seen.stream().anyMatch(output -> output.data().contains("streamed"))));
        }
    }

    /** Guide: options are read at the scope tmux will act on. */
    @Test
    void options(Server server) {
        Session session = server.sessions().get(0);
        server.globalOptions().set("base-index", "1");

        assertEquals(java.util.Optional.of("1"), session.options().get("base-index"));
    }

    /** Guide: a config file pins tmux's own configuration for a run. */
    @Test
    void pinningAConfigFile(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("tmux.conf");
        Files.writeString(config, "set -g base-index 5\n");

        ServerConfig pinned = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .configFile(config)
                .build();

        try (Server server = Server.open(pinned)) {
            Session session = server.newSession("configured");

            assertEquals(5, session.windows().get(0).index().value());
            server.killServer();
        }
    }

    private static boolean awaitOutput(Pane pane, String expected) throws InterruptedException {
        return await(() -> pane.capture().stream().anyMatch(line -> line.contains(expected)));
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
