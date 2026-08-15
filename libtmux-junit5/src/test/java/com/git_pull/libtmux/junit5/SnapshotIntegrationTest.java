package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.WindowId;
import com.git_pull.libtmux.snapshot.ClientState;
import com.git_pull.libtmux.snapshot.PaneState;
import com.git_pull.libtmux.snapshot.ServerSnapshot;
import com.git_pull.libtmux.snapshot.SessionState;
import com.git_pull.libtmux.snapshot.WindowContext;
import com.git_pull.libtmux.snapshot.WindowState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Capturing a real hierarchy, including the shape that a window-id-keyed model gets wrong. */
@ExtendWith(TmuxExtension.class)
final class SnapshotIntegrationTest {

    @Test
    void aCaptureSeesWhatTmuxReports(Server server) {
        server.cmd("new-window", "-t", "libtmux:", "-n", "second");
        server.cmd("split-window", "-t", "libtmux:");

        ServerSnapshot snapshot = server.snapshot();

        assertEquals(
                List.of("libtmux"),
                snapshot.sessions().stream().map(SessionState::name).toList());
        assertEquals(2, snapshot.windows().size());
        assertTrue(snapshot.panes().size() >= 3, "two windows, one of them split");
        // Against tmux's own answer rather than against nothing: the fixture attaches no client of
        // its own, but a control carrier attaches one to carry commands at all, and a capture is
        // right to report it. Comparing with list-clients holds whichever carrier is in force.
        assertEquals(
                server.cmd("list-clients", "-F", "#{client_name}").stdout(),
                snapshot.clients().stream().map(ClientState::name).toList(),
                "a capture reports the clients tmux does, whatever attached them");
    }

    /**
     * The case that decides how relations are keyed. tmux lists one window twice when it is linked
     * into two sessions, at a different index in each, and both links reach the same panes.
     */
    @Test
    void aWindowLinkedIntoTwoSessionsIsOneWindowAndTwoPositions(Server server) {
        server.cmd("new-session", "-d", "-s", "other");
        server.cmd("link-window", "-s", "libtmux:0", "-t", "other:9");

        ServerSnapshot snapshot = server.snapshot();

        List<WindowState> links = snapshot.windows().stream()
                .filter(window -> window.context().window().equals(sharedWindow(snapshot)))
                .toList();

        assertEquals(2, links.size(), "one window, listed once per session it is linked into");
        WindowContext first = links.get(0).context();
        WindowContext second = links.get(1).context();
        assertNotEquals(first, second, "different sessions make different winlinks");
        assertEquals(first.window(), second.window(), "the underlying window is the same one");
        assertEquals(2, Set.of(first, second).size(), "two winlinks must not collapse in a hash set");

        assertEquals(
                snapshot.panesOf(first).stream().map(PaneState::id).toList(),
                snapshot.panesOf(second).stream().map(PaneState::id).toList(),
                "both positions reach the same panes");
    }

    @Test
    void traversingACaptureAsksTmuxNothing(Server server) {
        ServerSnapshot snapshot = server.snapshot();
        server.cmd("new-window", "-t", "libtmux:", "-n", "appeared-after");

        assertEquals(1, snapshot.windows().size(), "a capture is a moment, not a live view");
        assertSame(snapshot.sessions(), snapshot.sessions(), "repeated reads are the same captured list");
        assertEquals(2, server.snapshot().windows().size(), "a new capture sees the new window");
    }

    private static WindowId sharedWindow(ServerSnapshot snapshot) {
        return snapshot.windows().stream()
                .map(window -> window.context().window())
                .filter(id -> snapshot.windows().stream()
                                .filter(window -> window.context().window().equals(id))
                                .count()
                        == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no window was linked twice"));
    }
}
