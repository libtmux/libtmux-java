package io.github.libtmux.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.UnsupportedTmuxVersion;
import io.github.libtmux.Window;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.TmuxTransport;
import io.github.libtmux.transport.TmuxTransportException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

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
        for (String layout : List.of(
                "even-horizontal",
                "even-vertical",
                "main-horizontal",
                "main-vertical",
                "tiled",
                "main-horizontal-mirrored",
                "main-vertical-mirrored")) {
            assertEquals(
                    Optional.of(layout),
                    WorkspaceBuilder.parse("session_name: s\nwindows:\n  - window_name: w\n    layout: " + layout)
                            .windows()
                            .get(0)
                            .layout());
        }
        assertEquals(
                Optional.of("8205,80x24,0,0{40x24,0,0,0,39x24,41,0,1}"),
                WorkspaceBuilder.parse(
                                "session_name: s\nwindows:\n  - window_name: w\n    layout: '8205,80x24,0,0{40x24,0,0,0,39x24,41,0,1}'")
                        .windows()
                        .get(0)
                        .layout(),
                "a serialized layout is a layout too");
    }

    @Test
    void aSerializedLayoutWithTheWrongChecksumIsRefusedBeforeTmuxSeesIt() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("""
                        session_name: dangerous
                        windows:
                          - window_name: one
                            layout: '0000,80x24,0,0,1'
                        """));

        assertTrue(String.valueOf(refused.getMessage()).contains("0000,80x24,0,0,1"));
    }

    @Test
    void aChecksumDoesNotMakeArbitraryTextALayout() {
        String malformed = serialized("not-a-layout");

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspaceBuilder.parse("session_name: dangerous\nwindows:\n  - layout: '" + malformed + "'\n"));

        assertTrue(String.valueOf(refused.getMessage()).contains(malformed));
    }

    @Test
    void serializedLayoutsUseTmuxsAsciiNumberGrammar() {
        String unicodeBody = serialized("١x1,0,0");
        String unicodeChecksum = "٨٢٠٥,80x24,0,0{40x24,0,0,0,39x24,41,0,1}";

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkspaceBuilder.parse("session_name: s\nwindows:\n  - layout: '" + unicodeBody + "'\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkspaceBuilder.parse("session_name: s\nwindows:\n  - layout: '" + unicodeChecksum + "'\n"));
    }

    @Test
    void aDescriptionTmuxCouldNotBuildIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("windows: []"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("session_name: nameless-windows"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("- not: a mapping"));
    }

    @Test
    void sessionNamesExcludeTmuxTargetDelimiters() {
        for (String name : List.of("with.dot", "with:colon")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorkspaceBuilder.parse("session_name: '" + name + "'\nwindows:\n  - window_name: one\n"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Workspace(
                            name, List.of(new WindowSpec("one", Optional.empty(), List.of(new PaneSpec(List.of()))))));
        }
    }

    @Test
    void malformedShapesNameTheExactYamlPath() {
        List<BadWorkspace> malformed = List.of(
                new BadWorkspace("""
                        session_name: typo
                        windows:
                          - window_name: one
                            panes:
                              - shell_commmand: echo lost
                        """, "$.windows[0].panes[0].shell_commmand"),
                new BadWorkspace("""
                        session_name: number
                        windows:
                          - window_name: one
                            panes:
                              - shell_command: 42
                        """, "$.windows[0].panes[0].shell_command"),
                new BadWorkspace("""
                        session_name: list
                        windows:
                          - window_name: one
                            panes:
                              - [echo valid, 42]
                        """, "$.windows[0].panes[0][1]"),
                new BadWorkspace("""
                        session_name: mapping
                        windows:
                          - window_name: one
                            panes: {shell_command: echo misplaced}
                        """, "$.windows[0].panes"),
                new BadWorkspace("""
                        session_name: mapping
                        windows: {window_name: misplaced}
                        """, "$.windows"),
                new BadWorkspace("""
                        session_name: unknown
                        windows:
                          - window_name: one
                        extra: true
                        """, "$.extra"));

        for (BadWorkspace bad : malformed) {
            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse(bad.yaml()));
            assertTrue(String.valueOf(failure.getMessage()).contains(bad.path()), failure.getMessage());
        }
    }

    @Test
    void duplicateKeysAndTrailingDocumentsAreRejected() {
        IllegalArgumentException duplicate =
                assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("""
                session_name: first
                session_name: second
                windows:
                  - window_name: one
                """));
        IllegalArgumentException trailing =
                assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.parse("""
                session_name: first
                windows:
                  - window_name: one
                ---
                session_name: ignored
                windows:
                  - window_name: two
                """));

        assertTrue(String.valueOf(duplicate.getMessage()).contains("duplicate"), duplicate.getMessage());
        assertTrue(String.valueOf(trailing.getMessage()).contains("document"), trailing.getMessage());
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

    @Test
    void anUnsafeProgrammaticLayoutNeverReachesTmux(Server server) {
        Workspace unsafe = new Workspace(
                "unsafe",
                List.of(new WindowSpec("one", Optional.of("0000,80x24,0,0,1"), List.of(new PaneSpec(List.of())))));

        assertThrows(IllegalArgumentException.class, () -> WorkspaceBuilder.build(server, unsafe));

        assertFalse(server.hasSession("unsafe"));
        assertTrue(server.isAlive(), "rejecting input must not let tmux inspect an unsafe layout");
    }

    @Test
    void anUnsupportedBuiltInLayoutIsRejectedBeforeAnyEffect() {
        AtomicBoolean effected = new AtomicBoolean();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.argv().get(0).equals("display-message")) {
                    return new CommandResult(
                            0, List.of(String.join(RowFormat.of("field").separator(), "4242", "3.4")), List.of());
                }
                effected.set(true);
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
        Workspace workspace = new Workspace(
                "portable",
                List.of(new WindowSpec(
                        "one", Optional.of("main-horizontal-mirrored"), List.of(new PaneSpec(List.of())))));

        try (Server old = Server.using(testConfig(), transport)) {
            assertThrows(UnsupportedTmuxVersion.class, () -> WorkspaceBuilder.build(old, workspace));
        }
        assertFalse(effected.get(), "version preflight must happen before new-session");
    }

    @Test
    void anUncertainCreationStillTargetsItsUniqueStagingSessionForCleanup() {
        AtomicReference<String> staged = new AtomicReference<>();
        AtomicReference<String> cleaned = new AtomicReference<>();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.argv().get(0).equals("new-session")) {
                    staged.set(request.argv().get(request.argv().indexOf("-s") + 1));
                    throw new TmuxTransportException("reply lost", DispatchOutcome.UNKNOWN, null);
                }
                if (request.argv().get(0).equals("kill-session")) {
                    cleaned.set(request.argv().get(request.argv().indexOf("-t") + 1));
                    return new CommandResult(0, List.of(), List.of());
                }
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
        Workspace workspace = new Workspace(
                "wanted", List.of(new WindowSpec("one", Optional.empty(), List.of(new PaneSpec(List.of())))));

        try (Server uncertain = Server.using(testConfig(), transport)) {
            assertThrows(TmuxTransportException.class, () -> WorkspaceBuilder.build(uncertain, workspace));
        }

        assertTrue(staged.get().startsWith("libtmux-ws-"));
        assertEquals("=" + staged.get(), cleaned.get());
    }

    @Test
    void topologyFailureRunsNoCommandsAndRollsBackTheExactSession(Server server, @TempDir Path directory)
            throws Exception {
        Path marker = directory.resolve("command-ran");
        Workspace workspace = new Workspace(
                "rolled-back",
                List.of(
                        new WindowSpec("first", Optional.empty(), List.of(new PaneSpec(List.of("touch " + marker)))),
                        new WindowSpec("invalid\u0000window", Optional.empty(), List.of(new PaneSpec(List.of())))));

        assertThrows(RuntimeException.class, () -> WorkspaceBuilder.build(server, workspace));
        Thread.sleep(250);

        assertFalse(Files.exists(marker), "commands must wait until every window and pane exists");
        assertFalse(server.hasSession("rolled-back"), "a failed build must not leave a partial session");
        assertEquals(
                List.of("libtmux"),
                server.sessions().stream().map(Session::name).toList());
    }

    @Test
    void cleanupFailureIsSuppressedOnTheApplicationFailure(Server server) {
        server.run(List.of("set-hook", "-g", "after-new-window", "kill-session -t =vanishing"));
        Workspace workspace = new Workspace(
                "vanishing",
                List.of(
                        new WindowSpec(
                                "first", Optional.empty(), List.of(new PaneSpec(List.of("invalid\u0000command")))),
                        new WindowSpec("second", Optional.empty(), List.of(new PaneSpec(List.of())))));

        RuntimeException failure =
                assertThrows(RuntimeException.class, () -> WorkspaceBuilder.build(server, workspace));

        assertTrue(failure.getSuppressed().length > 0, "cleanup must not replace the original application failure");
        assertEquals(
                List.of("libtmux"),
                server.sessions().stream().map(Session::name).toList());
    }

    @Test
    void aPaneCountMismatchCannotSilentlyDropCommands(Server server) {
        server.run(List.of("set-hook", "-g", "after-select-layout", "kill-pane -t =mismatched:0.1"));
        Workspace workspace = new Workspace(
                "mismatched",
                List.of(new WindowSpec(
                        "window",
                        Optional.of("tiled"),
                        List.of(new PaneSpec(List.of("echo first")), new PaneSpec(List.of("echo must-not-be-lost"))))));

        assertThrows(IllegalStateException.class, () -> WorkspaceBuilder.build(server, workspace));

        assertFalse(server.hasSession("mismatched"));
        assertEquals(
                List.of("libtmux"),
                server.sessions().stream().map(Session::name).toList());
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

    private static String serialized(String body) {
        int checksum = 0;
        for (int index = 0; index < body.length(); index++) {
            checksum = ((checksum >> 1) + ((checksum & 1) << 15) + body.charAt(index)) & 0xffff;
        }
        return "%04x,%s".formatted(checksum, body);
    }

    private static ServerConfig testConfig() {
        return ServerConfig.builder()
                .endpoint(ServerEndpoint.namedSocket("workspace-test"))
                .build();
    }

    private record BadWorkspace(String yaml, String path) {}
}
