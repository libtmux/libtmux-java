package io.github.libtmux.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Dimensions;
import io.github.libtmux.PaneEdges;
import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.WindowId;
import io.github.libtmux.WindowIndex;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A captured hierarchy that answers questions without asking tmux anything.
 *
 * <p>The relations are keyed on the winlink — session plus index plus window — rather than on the
 * window id, because a window linked into two sessions is one window and two positions. Keying on
 * the id alone would merge them and lose an ordering tmux considers distinct.
 */
final class ServerSnapshotTest {

    private static final Instant WHEN = Instant.parse("2026-08-14T00:00:00Z");
    private static final Dimensions SIZE = new Dimensions(80, 24);
    private static final Path PATH = Path.of("/tmp");
    private static final PaneEdges EDGES = new PaneEdges(true, true, true, true);

    private static final SessionId ALPHA = new SessionId("$0");
    private static final SessionId BETA = new SessionId("$1");
    private static final WindowId SHARED = new WindowId("@7");

    /** One window linked into two sessions, at a different index in each. */
    private static final WindowContext IN_ALPHA = new WindowContext(ALPHA, new WindowIndex(0), SHARED);

    private static final WindowContext IN_BETA = new WindowContext(BETA, new WindowIndex(3), SHARED);

    private static ServerSnapshot linked() {
        return ServerSnapshot.of(
                WHEN,
                List.of(new SessionState(ALPHA, "alpha", true, 1), new SessionState(BETA, "beta", false, 1)),
                List.of(
                        new WindowState(IN_ALPHA, "editor", true, 2, true, SIZE, "layout"),
                        new WindowState(IN_BETA, "editor", false, 2, true, SIZE, "layout")),
                List.of(
                        new PaneState(
                                IN_ALPHA,
                                new PaneId("%1"),
                                0,
                                true,
                                "nvim",
                                SIZE,
                                "t",
                                PATH,
                                1L,
                                EDGES,
                                Optional.of(false)),
                        new PaneState(
                                IN_ALPHA,
                                new PaneId("%2"),
                                1,
                                false,
                                "zsh",
                                SIZE,
                                "t",
                                PATH,
                                1L,
                                EDGES,
                                Optional.of(false)),
                        new PaneState(
                                IN_BETA,
                                new PaneId("%1"),
                                0,
                                true,
                                "nvim",
                                SIZE,
                                "t",
                                PATH,
                                1L,
                                EDGES,
                                Optional.of(false))),
                List.of(new ClientState("/dev/pts/3", Optional.of(ALPHA))));
    }

    @Test
    void aLinkedWindowIsOneWindowAndTwoPositions() {
        ServerSnapshot snapshot = linked();

        assertEquals(2, snapshot.windows().size(), "tmux lists the link twice and so do we");
        assertNotEquals(IN_ALPHA, IN_BETA, "the same window at two indexes is two winlinks");
        assertEquals(2, Set.of(IN_ALPHA, IN_BETA).size(), "two winlinks must not collapse in a hash set");
        assertEquals(
                SHARED,
                snapshot.windows().get(1).context().window(),
                "both links still report the one underlying window");
    }

    @Test
    void everyRelationIsReadableFromTheCaptureAlone() {
        ServerSnapshot snapshot = linked();

        assertEquals(
                List.of(IN_ALPHA),
                snapshot.windowsOf(ALPHA).stream().map(WindowState::context).toList());
        assertEquals(
                List.of(new PaneId("%1"), new PaneId("%2")),
                snapshot.panesOf(IN_ALPHA).stream().map(PaneState::id).toList());
        assertEquals(1, snapshot.panesOf(IN_BETA).size(), "the other link has its own panes");
    }

    @Test
    void lookupsFindWhatIsThereAndReportWhatIsNot() {
        ServerSnapshot snapshot = linked();

        assertEquals(Optional.of("alpha"), snapshot.session(ALPHA).map(SessionState::name));
        assertEquals(Optional.empty(), snapshot.session(new SessionId("$99")));
        assertEquals(List.of(), snapshot.windowsOf(new SessionId("$99")), "a miss is empty, not an error");
    }

    @Test
    void orderIsTmuxsDecisionAndIsPreserved() {
        ServerSnapshot snapshot = linked();

        assertEquals(
                List.of("alpha", "beta"),
                snapshot.sessions().stream().map(SessionState::name).toList());
        assertEquals(
                List.of(0, 1),
                snapshot.panesOf(IN_ALPHA).stream().map(PaneState::index).toList());
    }

    @Test
    void nothingCapturedCanBeMutatedAfterwards() {
        ServerSnapshot snapshot = linked();

        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.sessions().add(new SessionState(new SessionId("$9"), "x", false, 0)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.windowsOf(ALPHA).clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.panesOf(IN_ALPHA).clear());
    }

    @Test
    void aSnapshotSaysWhenItWasTaken() {
        assertEquals(WHEN, linked().capturedAt(), "a snapshot claims a moment, not current truth");
    }

    @Test
    void clientsAreCapturedWithWhateverTheyAreAttachedTo() {
        ServerSnapshot snapshot = linked();

        assertEquals(1, snapshot.clients().size());
        assertEquals(Optional.of(ALPHA), snapshot.clients().get(0).session());
    }

    @Test
    void aPaneWhoseWindowWasNeverCapturedIsARejectedCapture() {
        WindowContext orphan = new WindowContext(new SessionId("$5"), new WindowIndex(0), new WindowId("@5"));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerSnapshot.of(
                        WHEN,
                        List.of(),
                        List.of(),
                        List.of(new PaneState(
                                orphan,
                                new PaneId("%1"),
                                0,
                                true,
                                "zsh",
                                SIZE,
                                "t",
                                PATH,
                                1L,
                                EDGES,
                                Optional.empty())),
                        List.of()),
                "a pane under no captured window means the listings disagreed");
    }

    @Test
    void aRejectedCaptureNamesWhatDisagreed() {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> ServerSnapshot.of(
                        WHEN,
                        List.of(new SessionState(ALPHA, "alpha", true, 1)),
                        List.of(new WindowState(IN_BETA, "editor", false, 2, true, SIZE, "layout")),
                        List.of(),
                        List.of()));

        // Which window, and which session it wanted. A capture rejected in the field is a race
        // between four listings, and a message without the ids leaves nothing to reason from.
        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("@7"), message);
        assertTrue(message.contains("$1"), message);
    }

    @Test
    void aRejectedPaneCaptureNamesWhatDisagreed() {
        WindowContext orphan = new WindowContext(new SessionId("$5"), new WindowIndex(0), new WindowId("@5"));

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> ServerSnapshot.of(
                        WHEN,
                        List.of(),
                        List.of(),
                        List.of(new PaneState(
                                orphan,
                                new PaneId("%9"),
                                0,
                                true,
                                "zsh",
                                SIZE,
                                "t",
                                PATH,
                                1L,
                                EDGES,
                                Optional.empty())),
                        List.of()));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("%9"), message);
        assertTrue(message.contains("@5"), message);
    }
}
