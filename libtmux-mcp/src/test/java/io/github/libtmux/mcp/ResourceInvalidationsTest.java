package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.Dimensions;
import io.github.libtmux.PaneEdges;
import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.WindowId;
import io.github.libtmux.WindowIndex;
import io.github.libtmux.snapshot.ClientState;
import io.github.libtmux.snapshot.PaneState;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.SessionState;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.snapshot.WindowState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ResourceInvalidationsTest {

    private static final Instant WHEN = Instant.parse("2026-08-28T00:00:00Z");
    private static final SessionId SESSION = new SessionId("$1");
    private static final WindowContext WINDOW = new WindowContext(SESSION, new WindowIndex(0), new WindowId("@1"));
    private static final WindowContext OTHER_WINDOW =
            new WindowContext(SESSION, new WindowIndex(1), new WindowId("@2"));
    private static final PaneId PANE = new PaneId("%1");
    private static final PaneId OTHER_PANE = new PaneId("%2");
    private static final Dimensions SIZE = new Dimensions(80, 24);

    @Test
    void sessionRenameInvalidatesTheOldAndNewAddresses() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(WHEN.plusSeconds(1), "renamed", false, "zsh", SIZE, List.of());

        assertEquals(
                Set.of(
                        "tmux://sessions",
                        "tmux://sessions/alpha",
                        "tmux://sessions/renamed",
                        "tmux://panes",
                        "tmux://panes/%251"),
                changed(before, after));
    }

    @Test
    void paneMetadataChangeInvalidatesOnlyPaneMetadata() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(WHEN.plusSeconds(1), "alpha", false, "nvim", SIZE, List.of());

        assertEquals(Set.of("tmux://panes", "tmux://panes/%251"), changed(before, after));
    }

    @Test
    void addingOrRemovingAPaneInvalidatesItsAddressesAndServerCount() {
        PaneState first = pane(WINDOW, PANE, "zsh", SIZE);
        PaneState second = pane(WINDOW, OTHER_PANE, 1, "tail", SIZE);
        ServerSnapshot before = snapshot(WHEN, "alpha", false, List.of(window(WINDOW, "shell", 1)), List.of(first));
        ServerSnapshot after = snapshot(
                WHEN.plusSeconds(1), "alpha", false, List.of(window(WINDOW, "shell", 2)), List.of(first, second));
        Set<String> expected =
                Set.of("tmux://server", "tmux://panes", "tmux://panes/%252", "tmux://panes/%252/content");

        assertEquals(expected, changed(before, after));
        assertEquals(expected, changed(after, before));
    }

    @Test
    void addingOrRemovingASessionInvalidatesItsAddressAndServerCount() {
        ServerSnapshot empty = ServerSnapshot.of(WHEN, List.of(), List.of(), List.of(), List.of());
        ServerSnapshot present = ServerSnapshot.of(
                WHEN.plusSeconds(1),
                List.of(new SessionState(SESSION, "alpha", false, 0)),
                List.of(),
                List.of(),
                List.of());
        Set<String> expected = Set.of("tmux://server", "tmux://sessions", "tmux://sessions/alpha");

        assertEquals(expected, changed(empty, present));
        assertEquals(expected, changed(present, empty));
    }

    @Test
    void paneDimensionsAlsoInvalidateContent() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(WHEN.plusSeconds(1), "alpha", false, "zsh", new Dimensions(120, 40), List.of());

        assertEquals(Set.of("tmux://panes", "tmux://panes/%251", "tmux://panes/%251/content"), changed(before, after));
    }

    @Test
    void replacingAPaneProcessAlsoInvalidatesItsContent() {
        PaneState beforePane = pane(WINDOW, PANE, "zsh", SIZE, 11L);
        PaneState afterPane = pane(WINDOW, PANE, "sleep", SIZE, 12L);
        ServerSnapshot before =
                snapshot(WHEN, "alpha", false, List.of(window(WINDOW, "shell", 1)), List.of(beforePane));
        ServerSnapshot after =
                snapshot(WHEN.plusSeconds(1), "alpha", false, List.of(window(WINDOW, "shell", 1)), List.of(afterPane));

        assertEquals(Set.of("tmux://panes", "tmux://panes/%251", "tmux://panes/%251/content"), changed(before, after));
    }

    @Test
    void hierarchyCountsInvalidateTheServerResource() {
        PaneState pane = pane(WINDOW, PANE, "zsh", SIZE);
        ServerSnapshot before = snapshot(WHEN, "alpha", false, List.of(window(WINDOW, "shell", 1)), List.of(pane));
        ServerSnapshot after = snapshot(
                WHEN.plusSeconds(1),
                "alpha",
                false,
                List.of(window(WINDOW, "shell", 1), window(OTHER_WINDOW, "logs", 0)),
                List.of(pane));

        assertEquals(Set.of("tmux://server", "tmux://sessions", "tmux://sessions/alpha"), changed(before, after));
    }

    @Test
    void aGenerationGapInvalidatesAllOldAndNewKnownResources() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        PaneState replacement = pane(WINDOW, OTHER_PANE, "nvim", SIZE);
        ServerSnapshot after = snapshot(
                WHEN.plusSeconds(1), "renamed", false, List.of(window(WINDOW, "shell", 1)), List.of(replacement));

        assertEquals(
                Set.of(
                        "tmux://server",
                        "tmux://sessions",
                        "tmux://panes",
                        "tmux://sessions/alpha",
                        "tmux://sessions/renamed",
                        "tmux://panes/%251",
                        "tmux://panes/%251/content",
                        "tmux://panes/%252",
                        "tmux://panes/%252/content"),
                ResourceInvalidations.allKnown(project(before), project(after)));
    }

    @Test
    void paneOutputInvalidatesOnlyThatPanesContent() {
        assertEquals(Set.of("tmux://panes/%251/content"), ResourceInvalidations.output(PANE));
    }

    @Test
    void droppedOutputInvalidatesEveryKnownPaneContentOnce() {
        WindowContext linked = new WindowContext(SESSION, new WindowIndex(1), new WindowId("@1"));
        PaneState first = pane(WINDOW, PANE, "zsh", SIZE);
        PaneState duplicate = pane(linked, PANE, "zsh", SIZE);
        PaneState second = pane(WINDOW, OTHER_PANE, 1, "tail", SIZE);
        ServerSnapshot snapshot = snapshot(
                WHEN,
                "alpha",
                false,
                List.of(window(WINDOW, "shell", 2), window(linked, "linked", 1)),
                List.of(first, duplicate, second));

        assertEquals(
                Set.of("tmux://panes/%251/content", "tmux://panes/%252/content"),
                ResourceInvalidations.droppedOutput(project(snapshot)));
    }

    @Test
    void capturedTimeAloneProducesNoInvalidation() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(WHEN.plusSeconds(1), "alpha", false, "zsh", SIZE, List.of());

        assertEquals(Set.of(), changed(before, after));
    }

    @Test
    void hiddenClientsDoNotMakeSessionsAppearAttached() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(
                WHEN.plusSeconds(1),
                "alpha",
                true,
                "zsh",
                SIZE,
                List.of(new ClientState("watcher", Optional.of(SESSION))));

        assertEquals(
                Set.of(),
                ResourceInvalidations.between(
                        ResourceInvalidations.project(before, Set.of("watcher")::contains),
                        ResourceInvalidations.project(after, Set.of("watcher")::contains)));
    }

    @Test
    void visibleClientsMakeSessionsAppearAttached() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(
                WHEN.plusSeconds(1),
                "alpha",
                true,
                "zsh",
                SIZE,
                List.of(new ClientState("terminal", Optional.of(SESSION))));

        assertEquals(Set.of("tmux://sessions", "tmux://sessions/alpha"), changed(before, after));
    }

    @Test
    void changedUriSetsAreImmutable() {
        ServerSnapshot before = onePane(WHEN, "alpha", false, "zsh", SIZE, List.of());
        ServerSnapshot after = onePane(WHEN.plusSeconds(1), "alpha", false, "nvim", SIZE, List.of());
        Set<String> changed = changed(before, after);

        assertThrows(UnsupportedOperationException.class, () -> changed.add("tmux://server"));
    }

    private static Set<String> changed(ServerSnapshot before, ServerSnapshot after) {
        return ResourceInvalidations.between(project(before), project(after));
    }

    private static ResourceInvalidations.Projection project(ServerSnapshot snapshot) {
        return ResourceInvalidations.project(snapshot, ignored -> false);
    }

    private static ServerSnapshot onePane(
            Instant when,
            String sessionName,
            boolean attached,
            String command,
            Dimensions size,
            List<ClientState> clients) {
        return ServerSnapshot.of(
                when,
                List.of(new SessionState(SESSION, sessionName, attached, 1)),
                List.of(window(WINDOW, "shell", 1)),
                List.of(pane(WINDOW, PANE, command, size)),
                clients);
    }

    private static ServerSnapshot snapshot(
            Instant when, String sessionName, boolean attached, List<WindowState> windows, List<PaneState> panes) {
        return snapshot(when, sessionName, attached, windows, panes, List.of());
    }

    private static ServerSnapshot snapshot(
            Instant when,
            String sessionName,
            boolean attached,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        return ServerSnapshot.of(
                when,
                List.of(new SessionState(SESSION, sessionName, attached, windows.size())),
                windows,
                panes,
                clients);
    }

    private static WindowState window(WindowContext context, String name, int panes) {
        return new WindowState(context, name, true, panes, false, SIZE, "layout");
    }

    private static PaneState pane(WindowContext context, PaneId id, String command, Dimensions size) {
        return pane(context, id, 0, command, size, 1L);
    }

    private static PaneState pane(WindowContext context, PaneId id, String command, Dimensions size, long pid) {
        return pane(context, id, 0, command, size, pid);
    }

    private static PaneState pane(WindowContext context, PaneId id, int index, String command, Dimensions size) {
        return pane(context, id, index, command, size, 1L);
    }

    private static PaneState pane(
            WindowContext context, PaneId id, int index, String command, Dimensions size, long pid) {
        return new PaneState(
                context,
                id,
                index,
                true,
                command,
                size,
                "title",
                Path.of("/work"),
                pid,
                new PaneEdges(true, true, true, true),
                Optional.of(false));
    }
}
