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
                assertThrows(IllegalArgumentException.class, () -> server.cmd(command), command.toString());
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> server.batch()
                            .add("set-option", "-g", "@layout-effect", "changed")
                            .add("select-layout", "not-a-layout")
                            .run());
            assertThrows(
                    IllegalArgumentException.class,
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
}
