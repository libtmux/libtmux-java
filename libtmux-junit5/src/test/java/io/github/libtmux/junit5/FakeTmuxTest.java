package io.github.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ObjectDoesNotExistException;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The library, driven through a fake tmux, behaves as it does against a real one.
 *
 * <p>Every test here goes through the public API only, which is the point: a consumer's test never
 * sees the fake's internals either, and anything the library asks tmux that the fake answers wrongly
 * shows up as the library misbehaving.
 */
final class FakeTmuxTest {

    /**
     * A value holding {@code $} and a name is the one shape that makes the library ask which tmux it
     * is talking to, because 3.4 escapes it differently. The fake has to answer that, or the first
     * consumer to store a path in one meets a failure this double invented.
     */
    @Test
    void aValueThatMakesTheLibraryAskTheVersionStillRoundTrips() {
        FakeTmux tmux = new FakeTmux();
        tmux.addSession("work");

        try (Server server = tmux.server()) {
            server.environment().set("AWKWARD", "$HOME/bin");
            // The shape that ends a value, spelled inside one and then ending a line. The parser
            // takes the last terminator on a line, so a one-line forgery cannot fool it; a forged
            // one that ends a line can, and only the escaping tmux does stops it being read as the
            // end. This is what pins that the double escapes at all.
            server.environment().set("QUOTED", "before\"; export QUOTED;\nafter");
            server.environment().remove("WITHHELD");

            assertEquals(Optional.of("$HOME/bin"), server.environment().get("AWKWARD"));
            assertEquals(
                    Optional.of("before\"; export QUOTED;\nafter"),
                    server.environment().get("QUOTED"));
            assertTrue(server.environment().isRemoved("WITHHELD"));
            assertEquals(Optional.empty(), server.environment().get("NEVER_SET"));
        }
    }

    @Test
    void anEmptyServerHasNothingInIt() {
        FakeTmux tmux = new FakeTmux();
        try (Server server = tmux.server()) {
            assertEquals(List.of(), server.sessions());
            assertFalse(server.hasSession("work"));
        }
    }

    @Test
    void aSessionTheTestAddsIsASessionTheLibraryReads() {
        FakeTmux tmux = new FakeTmux();
        PaneId pane = tmux.addSession("work");

        try (Server server = tmux.server()) {
            Session work = server.session("work").orElseThrow();
            assertEquals("work", work.name());
            assertEquals(1, work.windows().size());
            assertEquals(pane, work.activePane().orElseThrow().id());
            assertTrue(server.hasSession("work"));
            assertEquals("work", work.expand("#{session_name}"));
        }
    }

    @Test
    void aPaneShowsWhatTheTestSaysItShows() {
        FakeTmux tmux = new FakeTmux();
        PaneId id = tmux.addSession("work");
        tmux.show(id, "$ make", "make: Nothing to be done.");

        try (Server server = tmux.server()) {
            assertEquals(
                    List.of("$ make", "make: Nothing to be done."),
                    server.pane(id).orElseThrow().capture());
        }
    }

    /** What the library creates is in the fake afterwards, and the handle it returns is to it. */
    @Test
    void creatingThroughTheLibraryChangesTheFake() {
        FakeTmux tmux = new FakeTmux();
        try (Server server = tmux.server()) {
            Session build = server.newSession("build");
            Window logs = build.newWindow("logs");
            Pane split = logs.split();

            assertEquals("build", build.name());
            assertEquals("logs", logs.name());
            assertEquals(2, logs.refresh().panes().size());
            assertTrue(server.panes().contains(split));
            assertEquals(List.of(build.id()), tmux.sessions());
        }
    }

    @Test
    void renamingAndRetitlingAreReadBack() {
        FakeTmux tmux = new FakeTmux();
        tmux.addSession("work");
        try (Server server = tmux.server()) {
            Session renamed = server.sessions().get(0).rename("play");
            Pane titled = renamed.activePane().orElseThrow().retitle("editor");

            assertEquals("play", server.sessions().get(0).name());
            assertEquals("editor", titled.title());
        }
    }

    /** Keys are recorded, as the words tmux would have read, and not run. */
    @Test
    void whatTheLibrarySentIsRecorded() {
        FakeTmux tmux = new FakeTmux();
        PaneId id = tmux.addSession("work");
        try (Server server = tmux.server()) {
            server.pane(id).orElseThrow().sendLine("make test");
        }

        assertTrue(
                tmux.sent().contains(List.of("send-keys", "-l", "-t", id.value(), "--", "make test\r")),
                "the command, unwrapped from the fence it travelled in: " + tmux.sent());
    }

    /** The characters tmux's command parser treats specially arrive as themselves. */
    @Test
    void awkwardTextIsRecordedExactlyAsItWasSent() {
        FakeTmux tmux = new FakeTmux();
        PaneId id = tmux.addSession("work");
        String awkward = "it's;\nfine\\ #{pid}";
        try (Server server = tmux.server()) {
            server.pane(id).orElseThrow().sendLiteral(List.of(awkward));
        }

        assertTrue(
                tmux.sent().stream().anyMatch(argv -> argv.getFirst().equals("send-keys") && argv.contains(awkward)),
                "a quote, a newline, a trailing semicolon and a backslash survived the trip: " + tmux.sent());
    }

    @Test
    void killingThroughTheLibraryRemovesFromTheFake() {
        FakeTmux tmux = new FakeTmux();
        tmux.addSession("work");
        try (Server server = tmux.server()) {
            server.killSession("work");

            assertEquals(List.of(), server.sessions());
        }
    }

    /**
     * A handle made against a server that was since replaced is refused — the fence is evaluated,
     * not assumed, so code that must survive a restart can be tested without one.
     */
    @Test
    void aHandleFromBeforeARestartIsRefused() {
        FakeTmux tmux = new FakeTmux();
        tmux.addSession("work");
        try (Server server = tmux.server()) {
            Session before = server.sessions().get(0);

            tmux.restart();
            tmux.addSession("work");

            assertThrows(ObjectDoesNotExistException.class, () -> before.rename("hijacked"));
            assertEquals("work", server.sessions().get(0).name(), "and the new session was left alone");
        }
    }

    @Test
    void aCommandTmuxDoesNotHaveFailsAsItDoesInTmux() {
        FakeTmux tmux = new FakeTmux();
        try (Server server = tmux.server()) {
            assertFalse(server.cmd("not-a-tmux-command").succeeded());
            assertTrue(server.cmd("display-message", "-p", "#{version}").succeeded());
        }
    }
}
