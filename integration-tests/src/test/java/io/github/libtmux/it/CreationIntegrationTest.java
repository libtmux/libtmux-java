package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Dimensions;
import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.UnsupportedTmuxVersion;
import io.github.libtmux.Window;
import io.github.libtmux.WindowSpec;
import io.github.libtmux.junit5.TmuxExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Making windows and sessions against a real tmux, on whichever release the lane is running.
 *
 * <p>Neither command changed its flags across the supported range, so what is version-dependent here
 * is the detached session size that 3.2a accepts and then ignores.
 */
@ExtendWith(TmuxExtension.class)
final class CreationIntegrationTest {

    private static final TmuxVersion HONOURS_EXTRAS_SINCE = new TmuxVersion(3, 3, "a");

    // ------------------------------------------------------------------------------ new-window

    @Test
    void aWindowCanBeNamedAndGivenACommand(Server server) throws InterruptedException {
        Session session = server.sessions().get(0);

        Window logs = session.newWindow(w -> w.named("logs").running("sleep", "30"));

        assertEquals("logs", logs.name());
        Pane pane = logs.activePane().orElseThrow();
        assertTrue(
                Await.until(() -> "sleep".equals(pane.refresh().currentCommand())),
                "the window's first pane never reported the command");
    }

    @Test
    void placementDecidesWhichSideOfTheCurrentWindowItLandsOn(Server server) {
        Session session = server.sessions().get(0);
        session.newWindow(w -> w.named("middle"));

        Window before = session.refresh().newWindow(w -> w.named("earlier").before());
        Window after = session.refresh().newWindow(w -> w.named("later").after());

        assertTrue(
                before.index().value() < after.index().value(),
                "before gave " + before.index() + ", after gave " + after.index());
    }

    /**
     * tmux reports nothing at all when {@code -S} reuses a window, so the handle comes from a lookup.
     * The thing that must not happen is a second window with the same name.
     */
    @Test
    void reusingAnExistingWindowReturnsItRatherThanMakingASecond(Server server) {
        Session session = server.sessions().get(0);
        Window first = session.newWindow(w -> w.named("reused"));

        Window again = session.refresh().newWindow(w -> w.named("reused").reuseExisting());

        assertEquals(first.id(), again.id(), "the same window came back");
        assertEquals(
                1,
                session.refresh().windows().stream()
                        .filter(window -> "reused".equals(window.name()))
                        .count(),
                "and no second window carries the name");
    }

    /**
     * {@code -k} replaces whatever holds a given index, so it only means anything when an index was
     * named. Without one tmux picks a free index and has nothing to destroy.
     */
    @Test
    void replacingDestroysWhateverHeldThatIndex(Server server) {
        Session session = server.sessions().get(0);
        Window victim = session.newWindow(w -> w.named("victim"));
        int held = victim.index().value();

        Window replacement = session.refresh()
                .newWindow(w -> w.named("replacement").atIndex(held).replaceExisting());

        assertNotEquals(victim.id(), replacement.id());
        assertEquals(held, replacement.index().value(), "the replacement took the index");
        assertTrue(
                session.refresh().windows().stream().noneMatch(window -> "victim".equals(window.name())),
                "the window that held the index is gone");
    }

    @Test
    void staleWinlinkCannotSelectItsReplacement(Server server) {
        Session session = server.sessions().get(0);
        Window stale = session.newWindow(w -> w.named("stale"));
        int held = stale.index().value();
        Window other = session.refresh().newWindow(w -> w.named("other"));
        Window replacement = session.refresh()
                .newWindow(w -> w.named("replacement").atIndex(held).replaceExisting());
        other.select();

        assertAll(
                () -> assertThrows(ObjectDoesNotExist.class, stale::select),
                () -> assertEquals(
                        other.id(),
                        session.refresh().activeWindow().orElseThrow().id(),
                        "the stale handle must not select the replacement"));
        assertNotEquals(stale.id(), replacement.id());
    }

    @Test
    void aWindowCommandStartsInTheRequestedDirectory(Server server, @TempDir Path directory) throws Exception {
        Session session = server.sessions().get(0);
        Path real = directory.toRealPath();
        Path written = directory.resolve("seen");
        session.newWindow(w -> w.named("elsewhere")
                .in(real)
                .running("/bin/sh", "-c", "pwd > \"$1\"; sleep 30", "probe", written.toString()));
        assertTrue(Await.until(() -> Files.exists(written)));
        assertEquals(real.toString(), Files.readString(written).strip());
    }

    @Test
    void aWindowInheritsTheEnvironmentItWasGiven(Server server, @TempDir Path directory) throws Exception {
        Session session = server.sessions().get(0);
        Path written = directory.resolve("seen");

        session.newWindow(w -> w.named("env")
                .env("LIBTMUX_W", "carried")
                .running("sh", "-c", "printf '%s' \"$LIBTMUX_W\" > " + written + "; sleep 30"));

        assertTrue(Await.until(() -> Files.exists(written)), "the command never ran");
        assertEquals("carried", Files.readString(written));
    }

    // ----------------------------------------------------------------------------- new-session

    @Test
    void aSessionCanNameItsFirstWindowAndRunSomethingInIt(Server server) throws InterruptedException {
        Session built = server.newSession(
                s -> s.named("built").firstWindowNamed("editor").running("sleep", "30"));

        assertEquals("built", built.name());
        assertEquals("editor", built.windows().get(0).name());
        Pane pane = built.activePane().orElseThrow();
        assertTrue(Await.until(() -> "sleep".equals(pane.refresh().currentCommand())));
    }

    /** 3.2a accepts {@code -x}/{@code -y} for a detached session and gives it the default size. */
    @Test
    void aSizeIsHonouredOrRefusedDependingOnTheRelease(Server server) {
        Dimensions wanted = new Dimensions(100, 40);

        if (server.version().atLeast(HONOURS_EXTRAS_SINCE)) {
            Session sized = server.newSession(s -> s.named("sized").sized(wanted));

            assertEquals(wanted, sized.windows().get(0).size());
        } else {
            assertThrows(
                    UnsupportedTmuxVersion.class,
                    () -> server.newSession(s -> s.named("sized").sized(wanted)));
            assertTrue(
                    server.sessions().stream().noneMatch(session -> "sized".equals(session.name())),
                    "a refused spec must not have reached tmux");
        }
    }

    @Test
    void aSessionCanBeMadeBeforeAnythingHasAskedTheServerItsVersion(@TempDir Path directory) throws Exception {
        // The bootstrap case: on a socket with no server, nothing can answer #{version} yet.
        Path config = directory.resolve("empty.conf");
        Files.writeString(config, "");

        try (Server fresh = Server.open(io.github.libtmux.ServerConfig.builder()
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .endpoint(io.github.libtmux.ServerEndpoint.socketPath(directory.resolve("s")))
                .configFile(config)
                .build())) {
            Session first = fresh.newSession(s -> s.named("bootstrap"));

            assertEquals("bootstrap", first.name());
            fresh.killServer();
        }
    }

    @Test
    void oneSpecDescribesTheWindowOfTwoDifferentSessions(Server server) {
        WindowSpec shape = WindowSpec.builder().named("shared").after().build();
        Session first = server.sessions().get(0);
        Session second = server.newSession(s -> s.named("other"));

        Window fromFirst = first.newWindow(shape);
        Window fromSecond = second.newWindow(shape);

        assertEquals("shared", fromFirst.name());
        assertEquals("shared", fromSecond.name());
        assertNotEquals(fromFirst.id(), fromSecond.id());
    }

    @Test
    void aSessionSpecIsADescriptionThatCanBeAppliedTwice(Server server) {
        SessionSpec shape = SessionSpec.builder().firstWindowNamed("main").build();

        Session first = server.newSession(shape);
        Session second = server.newSession(shape);

        assertNotEquals(first.id(), second.id(), "tmux numbered them apart");
        assertEquals("main", first.windows().get(0).name());
        assertEquals("main", second.windows().get(0).name());
    }
}
