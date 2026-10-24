package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.Window;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Selective refusals preserve ordinary operations at the core floor. */
@ExtendWith(TmuxExtension.class)
final class OptionalFeatureIntegrationTest {
    private static final TmuxVersion BUFFER = new TmuxVersion(3, 4, "");
    // Mirrors Capabilities.CLIPBOARD_QUERY.since(), which this module cannot reach; the registry
    // test pins that value.
    private static final TmuxVersion CLIPBOARD = new TmuxVersion(3, 6, "a");

    @Test
    void malformedLiteralLayoutsCannotApplyAnEarlierBatchCommand(Server server) {
        Pane pane = server.panes().getFirst();
        if (!server.version().atLeast(BUFFER)) {
            for (List<String> command : List.of(
                    List.of("select-layout", "not-a-layout"),
                    List.of("selectl", "-t" + pane.id().value(), "not-a-layout"),
                    List.of("select-l", "--", "-n"),
                    List.of("select-layout", "-ot", pane.id().value(), "tile"),
                    List.of("select-layout", "0000,80x24,0,0,0"))) {
                assertThrows(UnsupportedFeatureException.class, () -> server.cmd(command), command.toString());
            }
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> server.batch()
                            .add("set-option", "-g", "@layout-effect", "changed")
                            .add("select-layout", "not-a-layout")
                            .run());
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> pane.batch()
                            .add("set-option", "-g", "@layout-effect", "changed")
                            .add("select-layout", "-o", "tiled")
                            .run());
            assertEquals(
                    List.of(),
                    server.cmd("show-options", "-gqv", "@layout-effect").stdout());
        } else {
            assertFalse(server.cmd("select-layout", "not-a-layout").succeeded());
        }
        assertTrue(server.isAlive());
    }

    /**
     * {@code refresh-client -l} asks the attached terminal for its clipboard, and on 3.3a the server
     * exits when the terminal answers. A pty client is attached because without one the command only
     * says there is no current client. The positive control runs only where the query is safe, and
     * the client leaves right after it: before 3.6a that departure ends the daemon too.
     */
    @Test
    void clipboardQueryIsRefusedBelowItsReleaseAndTheDaemonStaysUp(Server server) throws Exception {
        try (NativeClient client = NativeClient.attach(server.sessions().getFirst())) {
            String name = server.clients().getFirst().name();
            List<List<String>> spellings = List.of(
                    List.of("refresh-client", "-t", name, "-l"),
                    List.of("refresh-client", "-l"),
                    List.of("refresh", "-l"),
                    List.of("refresh-c", "-l"),
                    List.of("ref", "-lt", name),
                    List.of("refresh-client", "-l", "--"));
            if (!server.version().atLeast(CLIPBOARD)) {
                for (List<String> command : spellings) {
                    assertThrows(UnsupportedFeatureException.class, () -> server.cmd(command), command.toString());
                    assertThrows(
                            UnsupportedFeatureException.class,
                            () -> server.batch()
                                    .add("list-sessions")
                                    .add(command)
                                    .run(),
                            "batch " + command);
                    assertTrue(server.isAlive(), command.toString());
                    assertTrue(client.isAlive(), command.toString());
                }
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> server.chain()
                                .then("list-sessions")
                                .then("refresh", "-l")
                                .run());
                // The valued flag swallows -l, so this is not a clipboard query and stays allowed.
                assertTrue(server.cmd("refresh-client", "-t", name).succeeded());
                assertTrue(server.cmd("refresh", "-S", "-t", name).succeeded());
            } else {
                assertTrue(server.cmd("refresh-client", "-t", name, "-l").succeeded());
            }
            assertTrue(server.isAlive());
            assertTrue(client.isAlive());
        }
    }

    /**
     * The failure on 3.4 to 3.6 is a race between the query and its client leaving, so the query and
     * the client's detach travel in one tmux invocation, which leaves the terminal no time to answer.
     * Below 3.6a the batch is refused whole; from 3.6a it runs, and either way the daemon is read
     * afterwards. Without the refusal the daemon of a 3.5 or 3.6 server does not survive this.
     */
    @Test
    void aClientLeavingAfterTheQueryCannotEndTheDaemon(Server server) throws Exception {
        try (NativeClient client = NativeClient.attach(server.sessions().getFirst())) {
            String name = server.clients().getFirst().name();
            if (server.version().atLeast(CLIPBOARD)) {
                server.batch()
                        .add("refresh-client", "-t", name, "-l")
                        .add("detach-client", "-t", name)
                        .run();
            } else {
                assertThrows(
                        UnsupportedFeatureException.class,
                        () -> server.batch()
                                .add("refresh-client", "-t", name, "-l")
                                .add("detach-client", "-t", name)
                                .run());
                assertTrue(client.isAlive(), "a refused batch must not have detached the client");
                server.cmd("detach-client", "-t", name);
            }
            assertTrue(Await.until(() -> !client.isAlive()));
        }
        // The server ends a moment after the client is gone, not while it leaves.
        Thread.sleep(1000);
        assertTrue(server.isAlive());
        assertTrue(server.cmd("list-sessions").succeeded());
    }

    @Test
    void literalLayoutsKeepPresetsSerializedTreesAndIgnoredOperands(Server server) {
        String target = server.panes().getFirst().id().value();
        assertTrue(server.cmd("select-l", "-t", target, "tile").succeeded());
        assertTrue(server.cmd("selectl", "-t" + target, "even-h").succeeded());
        String serialized =
                server.cmd("display-message", "-p", "#{window_layout}").stdout().getFirst();
        assertTrue(server.cmd("select-layout", "-ot" + target, "--", serialized).succeeded());
        for (String flag : List.of("-n", "-p", "-E")) {
            assertTrue(server.cmd("select-layout", flag, "not-a-layout").succeeded(), flag);
        }
        assertTrue(server.cmd("select-layout", "-o").succeeded());
        assertTrue(server.cmd("select-layout").succeeded());
        assertTrue(server.isAlive());
    }

    @Test
    void namedBufferRefusalPrecedesEveryRawAndCapturedBatchEffect(Server server) {
        server.buffers().set("user", "keep");
        Pane pane = server.panes().getFirst();
        if (!server.version().atLeast(BUFFER)) {
            assertThrows(UnsupportedFeatureException.class, () -> server.cmd("del", "-bmissing"));
            assertThrows(UnsupportedFeatureException.class, () -> server.cmd("setb", "-bmissing", "-nrenamed"));
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> server.batch()
                            .add("set-option", "-g", "@forbidden", "changed")
                            .add("delete-buffer", "-b", "missing")
                            .run());
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> pane.batch()
                            .add("set-option", "-g", "@forbidden", "changed")
                            .add("set-buffer", "-b", "missing", "-n", "renamed")
                            .run());
            assertEquals(
                    List.of(), server.cmd("show-options", "-gqv", "@forbidden").stdout());
            assertEquals("keep", server.buffers().show("user"));
        } else {
            pane.batch().add("set-buffer", "-b", "user", "-n", "renamed").run();
            assertEquals("keep", server.buffers().show("renamed"));
            assertFalse(server.cmd("delete-buffer", "-b", "missing").succeeded());
            assertEquals("keep", server.buffers().show("renamed"));
        }
    }

    @Test
    void optionAliasesAndFlagValuesRetainTheirNativeMeaning(Server server) {
        assertTrue(server.cmd("set", "-g", "@plain", "kept").succeeded());
        assertTrue(server.cmd("setw", "-g", "@window", "kept").succeeded());
        assertTrue(server.cmd("setb", "-b", "-n", "literal").succeeded());
        assertEquals("literal", server.buffers().show("-n"));
    }

    @Test
    void directNamedPasteWithDeleteRemainsAvailableAtTheCoreFloor(Server server) throws Exception {
        Pane pane = server.panes().getFirst();
        pane.respawn("cat");
        assertTrue(Await.until(() -> pane.expand("#{pane_current_command}").equals("cat")));
        server.buffers().set("exact", "direct-retained");
        assertTrue(
                server.cmd("paste-buffer", "-d", "-b", "exact", "-t", pane.id().value())
                        .succeeded());
        assertTrue(Await.until(() -> String.join("\n", pane.capture()).contains("direct-retained")));
        assertTrue(server.buffers().list().isEmpty());
    }

    @Test
    void managedPasteRequiresCleanupBeforeStagingOrCallingUserCode(Server server) throws Exception {
        Pane pane = server.panes().getFirst();
        pane.respawn("cat");
        assertTrue(Await.until(() -> pane.expand("#{pane_current_command}").equals("cat")));
        server.buffers().set("user", "keep");
        AtomicBoolean callback = new AtomicBoolean();
        if (!server.version().atLeast(BUFFER)) {
            assertThrows(UnsupportedFeatureException.class, () -> pane.paste("managed-forbidden"));
            assertThrows(
                    UnsupportedFeatureException.class, () -> pane.paste("managed-forbidden", () -> callback.set(true)));
            assertFalse(callback.get());
            assertEquals("keep", server.buffers().show("user"));
            assertEquals(1, server.buffers().list().size());
            assertFalse(String.join("\n", pane.capture()).contains("managed-forbidden"));
        } else {
            pane.paste("managed-retained", () -> callback.set(true));
            assertTrue(callback.get());
            assertTrue(Await.until(() -> String.join("\n", pane.capture()).contains("managed-retained")));
            assertEquals(1, server.buffers().list().size());
        }
    }

    @Test
    void anUnnamedSinglePaneBreakPreservesTheExistingWindowName(Server server) {
        Pane pane = server.panes().getFirst();
        assertEquals("preserved", pane.window().rename("preserved").name());
        if (server.version().equals(new TmuxVersion(3, 7, ""))) {
            assertThrows(UnsupportedFeatureException.class, pane::breakOut);
            assertEquals("preserved", server.windows().getFirst().name());
        } else assertEquals("preserved", pane.breakOut().name());
        assertTrue(server.isAlive());
    }

    @Test
    void aNamedBreakRetainsItsNameOnSingleAndMultiplePaneWindows(Server server) {
        Pane pane = server.panes().getFirst();
        Window single = pane.breakOut("single");
        assertEquals("single", single.name());
        Pane sibling = single.panes().getFirst().split();
        assertEquals("multiple", sibling.breakOut("multiple").name());
        assertEquals(2, server.windows().size());
        assertTrue(server.isAlive());
    }

    @Test
    void percentageSplitsUseTheWorkingTypedFormOnExact34(Server server) {
        Pane pane = server.panes().getFirst();
        if (server.version().equals(BUFFER)) {
            assertThrows(
                    UnsupportedFeatureException.class,
                    () -> server.cmd("sp", "-p", "30", "-t", pane.id().value()));
            assertEquals(1, server.panes().size());
        } else
            assertTrue(
                    server.cmd("split-window", "-d", "-p", "30", "-t", pane.id().value())
                            .succeeded());
        assertFalse(pane.split(spec -> spec.percent(30)).id().equals(pane.id()));
    }

    @Test
    void exactly37RefusesADelimiterInANameBeforeAnyEarlierCommandRuns(Server server) {
        String name = "split.name";
        if (server.version().sameRelease(new TmuxVersion(3, 7, ""))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> server.batch()
                            .add("set-option", "-g", "@name-effect", "changed")
                            .add("new-window", "-d", "-n", name)
                            .run());
            assertThrows(IllegalArgumentException.class, () -> server.cmd("rename-session", "colon:name"));
            assertThrows(IllegalArgumentException.class, () -> server.cmd("new-session", "-d", "-s", name));
            assertThrows(IllegalArgumentException.class, () -> server.cmd("new-window", "-dn" + name));
            assertEquals(
                    List.of(),
                    server.cmd("show-options", "-gqv", "@name-effect").stdout());
            assertEquals(1, server.windows().size());
        } else {
            assertTrue(server.cmd("new-window", "-d", "-n", name).succeeded());
        }
    }
}
