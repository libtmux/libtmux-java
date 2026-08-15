package io.github.libtmux.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Building a session from a written description, and refusing to build one tmux would not survive. */
@ExtendWith(TmuxExtension.class)
final class WorkspaceBuilderTest {

    private static final String WORKSPACE = """
            session_name: built
            windows:
              - window_name: editor
                layout: even-horizontal
                panes:
                  - shell_command: echo editor-pane-one
                  - shell_command: echo editor-pane-two
              - window_name: server
                panes:
                  - echo server-pane
            """;

    // ------------------------------------------------------------------------------- reading

    @Test
    void aWorkspaceIsReadBeforeAnythingIsBuilt() {
        Workspace workspace = WorkspaceBuilder.parse(WORKSPACE);

        assertEquals("built", workspace.sessionName());
        assertEquals(
                List.of("editor", "server"),
                workspace.windows().stream().map(WindowSpec::name).toList());
        assertEquals(Optional.of("even-horizontal"), workspace.windows().get(0).layout());
        assertEquals(2, workspace.windows().get(0).panes().size());
        assertEquals(
                List.of("echo server-pane"),
                workspace.windows().get(1).panes().get(0).commands());
    }

    @Test
    void aPaneMayBeATextACommandListOrAMapping() {
        Workspace workspace = WorkspaceBuilder.parse("""
                session_name: shapes
                windows:
                  - window_name: one
                    panes:
                      - echo bare
                      - shell_command:
                          - echo first
                          - echo second
                """);

        List<PaneSpec> panes = workspace.windows().get(0).panes();
        assertEquals(List.of("echo bare"), panes.get(0).commands());
        assertEquals(List.of("echo first", "echo second"), panes.get(1).commands());
    }

    @Test
    void aWindowWithNoPanesStatedStillGetsTheOneTmuxMakes() {
        Workspace workspace = WorkspaceBuilder.parse("""
                session_name: bare
                windows:
                  - window_name: only
                """);

        assertEquals(1, workspace.windows().get(0).panes().size());
        assertEquals(List.of(), workspace.windows().get(0).panes().get(0).commands());
    }

    /**
     * An unrecognised layout name is not merely rejected by tmux: on 3.3a it crashes the server and
     * takes every session on that socket with it, including ones this program never created. A
     * workspace file is user-supplied text, so the name is checked before tmux ever sees it.
     */
    @Test
    void aLayoutTmuxWouldNotRecogniseIsRefusedWhileItIsStillText() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("""
                        session_name: dangerous
                        windows:
                          - window_name: one
                            layout: not-a-real-layout
                        """));

        assertTrue(String.valueOf(refused.getMessage()).contains("not-a-real-layout"), "the message must name it");
    }

    @Test
    void everyLayoutTmuxDoesRecogniseIsAccepted() {
        for (String layout : List.of("even-horizontal", "even-vertical", "main-horizontal", "main-vertical", "tiled")) {
            assertEquals(
                    Optional.of(layout),
                    WorkspaceBuilder.parse("session_name: s\nwindows:\n  - window_name: w\n    layout: " + layout)
                            .windows()
                            .get(0)
                            .layout());
        }
        assertEquals(
                Optional.of("bb62,80x24,0,0{40x24,0,0,1,39x24,41,0,2}"),
                WorkspaceBuilder.parse(
                                "session_name: s\nwindows:\n  - window_name: w\n    layout: 'bb62,80x24,0,0{40x24,0,0,1,39x24,41,0,2}'")
                        .windows()
                        .get(0)
                        .layout(),
                "a serialized layout is a layout too");
    }

    @Test
    void aDescriptionTmuxCouldNotBuildIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("windows: []"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("session_name: nameless-windows"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("- not: a mapping"));
    }

    // ------------------------------------------------------------------------------ building

    @Test
    void theDescribedSessionIsWhatGetsBuilt(Server server) {
        Session built = WorkspaceBuilder.build(server, WorkspaceBuilder.parse(WORKSPACE));

        assertEquals("built", built.name());
        assertEquals(
                List.of("editor", "server"),
                built.windows().stream().map(Window::name).toList(),
                "the first window is renamed, not added alongside the one tmux made");
        assertEquals(2, built.windows().get(0).panes().size());
        assertEquals(1, built.windows().get(1).panes().size());
    }

    /**
     * The layout is even-horizontal deliberately. main-vertical gives its main pane
     * main-pane-width columns, which on an 80-column session leaves the other pane one column
     * wide: the command still runs, but nothing readable can be captured from it.
     */
    @Test
    void eachPaneRunsWhatItWasGiven(Server server) throws Exception {
        Session built = WorkspaceBuilder.build(server, WorkspaceBuilder.parse(WORKSPACE));

        List<Pane> editor = built.windows().get(0).panes();
        assertTrue(awaitOutput(editor.get(0), "editor-pane-one"), "the first pane never ran its command");
        assertTrue(awaitOutput(editor.get(1), "editor-pane-two"), "the second pane never ran its command");
    }

    @Test
    void buildingLeavesTheSessionTheFixtureAlreadyHad(Server server) {
        WorkspaceBuilder.build(server, WorkspaceBuilder.parse(WORKSPACE));

        assertEquals(2, server.sessions().size(), "a workspace adds a session, it does not take one over");
    }

    private static boolean awaitOutput(Pane pane, String expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (pane.capture().stream().anyMatch(line -> line.contains(expected))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
