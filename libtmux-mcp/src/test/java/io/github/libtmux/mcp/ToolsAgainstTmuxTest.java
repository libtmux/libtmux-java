package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The rest of the surface, against real tmux. */
@ExtendWith(TmuxExtension.class)
final class ToolsAgainstTmuxTest {

    // ---------------------------------------------------------------- knowing where you are

    @Test
    void panesAreListedWithTheIdOtherToolsTake(Server server) {
        Listings.Panes panes = Listings.panes(TestCalls.on(server));

        assertEquals(1, panes.count());
        assertTrue(panes.panes().get(0).id().startsWith("%"));
        assertEquals("libtmux", panes.panes().get(0).session());
        assertTrue(panes.panes().get(0).active());
        assertNull(panes.panes().get(0).caller(), "this process is not running inside the fixture");
    }

    /**
     * A filter that selects nothing is the case a model cannot tell from an empty server, so the
     * answer says which it was.
     */
    @Test
    void aFilterMatchingNothingSaysHowManyThereWere(Server server) {
        Object filter = java.util.Map.of(
                "schema",
                "libtmux.filter/1",
                "model",
                "pane",
                "expr",
                java.util.Map.of(
                        "node", "compare", "field", "pane_current_command", "op", "starts_with", "value", "nvim"));

        Listings.Panes panes = Listings.panes(TestCalls.on(server, "filter", filter));

        assertEquals(0, panes.count());
        assertTrue(String.valueOf(panes.note()).contains("without 'filter'"), String.valueOf(panes.note()));
    }

    @Test
    void whoamiSaysWhichServerAndThatNoPaneIsSpecial(Server server) {
        Listings.Whoami whoami = Listings.whoami(server, Caller.nowhere(), Safety.MUTATING);

        assertEquals(1, whoami.sessions());
        assertEquals(1, whoami.panes());
        assertEquals("mutating", whoami.safety());
        assertNull(whoami.callerPane());
        assertTrue(whoami.note().contains("no pane here is special"), whoami.note());
        assertNotNull(whoami.socket());
    }

    /** And when this process really is inside a pane, that pane is named as the one to protect. */
    @Test
    void whoamiNamesTheCallersOwnPaneWhenThereIsOne(Server server) {
        String pane = server.panes().get(0).id().value();
        Call call = TestCalls.asCaller(server, pane);

        Listings.Whoami whoami = Listings.whoami(server, call.caller(), Safety.DESTRUCTIVE);

        assertEquals(pane, whoami.callerPane());
        assertTrue(whoami.note().contains("confirm_self"), whoami.note());
    }

    @Test
    void panesAreMarkedWhenTheyAreTheCallersOwn(Server server) {
        String pane = server.panes().get(0).id().value();

        Listings.Panes panes = Listings.panes(TestCalls.asCaller(server, pane));

        assertEquals(true, panes.panes().get(0).caller(), "the pane this process runs in is marked");
    }

    @Test
    void windowsAndSessionsAreListedWithTheirIds(Server server) {
        server.sessions().get(0).newWindow("second");

        Listings.Windows windows = Listings.windows(TestCalls.on(server));
        Listings.Sessions sessions = Listings.sessions(server);

        assertEquals(2, windows.count());
        assertTrue(windows.windows().stream().allMatch(window -> window.id().startsWith("@")));
        assertEquals(1, sessions.count());
        assertTrue(sessions.sessions().get(0).windowNames().contains("second"));
    }

    @Test
    void nothingAttachedIsReportedAsNobodyWatching(Server server) {
        Listings.Clients clients = Listings.clients(TestCalls.on(server));

        assertEquals(0, clients.count());
        assertTrue(String.valueOf(clients.note()).contains("no person is watching"), String.valueOf(clients.note()));
    }

    // ---------------------------------------------------------------- refusing to end the conversation

    /**
     * The guard that matters. A model told to tidy up must not be able to kill the pane it is
     * speaking through by accident — but must still be able to when that is really what was meant.
     */
    @Test
    void killingTheCallersOwnPaneIsRefusedUntilItIsConfirmed(Server server) {
        String pane = server.sessions().get(0).windows().get(0).split().id().value();

        IllegalStateException refused = assertThrows(
                IllegalStateException.class, () -> Shaping.kill(TestCalls.asCaller(server, pane, "target", pane)));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("tmux_whoami"), message);
        assertTrue(message.contains("confirm_self"), message);
        assertEquals(2, server.panes().size(), "and the pane is still there");
    }

    @Test
    void confirmingIsEnoughToEndTheCallersOwnPane(Server server) {
        String pane = server.sessions().get(0).windows().get(0).split().id().value();

        Shaping.kill(TestCalls.asCaller(server, pane, "target", pane, "confirm_self", true));

        assertEquals(1, server.panes().size());
    }

    /** The window holding the caller's pane is as fatal as the pane itself. */
    @Test
    void killingAWindowHoldingTheCallersPaneIsRefusedToo(Server server) {
        var window = server.sessions().get(0).newWindow("doomed");
        String pane = window.panes().get(0).id().value();

        assertThrows(
                IllegalStateException.class,
                () -> Shaping.kill(
                        TestCalls.asCaller(server, pane, "target", window.id().value())));

        assertEquals(2, server.windows().size());
    }

    @Test
    void aPaneThatIsNotTheCallersIsEndedWithoutCeremony(Server server) {
        String mine = server.panes().get(0).id().value();
        String other = server.sessions().get(0).windows().get(0).split().id().value();

        Shaping.Ended ended = Shaping.kill(TestCalls.asCaller(server, mine, "target", other));

        assertEquals("pane", ended.kind());
        assertEquals(1, server.panes().size());
    }

    // ---------------------------------------------------------------- making things

    @Test
    void aWindowIsMadeWithoutMovingWhatAPersonIsLookingAt(Server server) {
        String active =
                server.sessions().get(0).activeWindow().orElseThrow().id().value();

        Shaping.Made made = Shaping.newWindow(TestCalls.on(server, "session", "libtmux", "name", "built"));

        assertTrue(made.id().startsWith("@"));
        assertTrue(String.valueOf(made.paneId()).startsWith("%"));
        assertEquals(
                active,
                server.sessions().get(0).activeWindow().orElseThrow().id().value(),
                "made detached, so the active window did not move");
    }

    @Test
    void aSplitHandsBackTheNewPaneAndKeepsTheOld(Server server) {
        String original = server.panes().get(0).id().value();

        Shaping.Made made = Shaping.splitPane(TestCalls.on(server, "pane_id", original, "direction", "right"));

        assertEquals(2, server.panes().size());
        assertTrue(server.panes().stream().anyMatch(pane -> pane.id().value().equals(made.id())));
        assertTrue(server.panes().stream().anyMatch(pane -> pane.id().value().equals(original)));
    }

    @Test
    void aDirectionNobodyRecognisesSaysWhichOnesExist(Server server) {
        String pane = server.panes().get(0).id().value();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> Shaping.splitPane(TestCalls.on(server, "pane_id", pane, "direction", "sideways")));

        assertTrue(String.valueOf(refused.getMessage()).contains("below"), refused.getMessage());
    }

    /** One document, one call, and the ids of everything it built. */
    @Test
    void aWholeSessionIsBuiltFromOneDocument(Server server) {
        String document = """
                session_name: built-from-a-document
                windows:
                  - window_name: editor
                    panes:
                      - echo editing
                  - window_name: services
                    layout: even-horizontal
                    panes:
                      - echo one
                      - echo two
                """;

        Workspaces.Built built = Workspaces.apply(TestCalls.on(server, "workspace", document));

        assertEquals("built-from-a-document", built.session());
        assertEquals(2, built.windows());
        assertEquals(3, built.panes());
        assertTrue(built.paneIds().stream().allMatch(pane -> pane.id().startsWith("%")));
        assertTrue(server.hasSession("built-from-a-document"));
    }

    @Test
    void aWorkspaceNamingASessionThatExistsIsRefusedBeforeAnythingIsBuilt(Server server) {
        String document = "session_name: libtmux\nwindows:\n  - window_name: w\n    panes:\n      - echo hi\n";

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> Workspaces.apply(TestCalls.on(server, "workspace", document)));

        assertTrue(String.valueOf(refused.getMessage()).contains("already there"), refused.getMessage());
        assertEquals(1, server.sessions().size());
    }

    // ---------------------------------------------------------------- input and channels

    @Test
    void keysAreSentByNameSoAnInterruptInterrupts(Server server) {
        String pane = server.panes().get(0).id().value();
        server.run(List.of("send-keys", "-l", "-t", pane, "sleep 60"));
        server.run(List.of("send-keys", "-t", pane, "Enter"));

        Typing.Sent sent = Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of("C-c")));

        assertEquals(1, sent.keys());
        assertFalse(sent.literal());
        assertTrue(String.valueOf(sent.note()).contains("not waited for"), String.valueOf(sent.note()));
    }

    @Test
    void sendingNoKeysAtAllSaysWhatWasWanted(Server server) {
        String pane = server.panes().get(0).id().value();

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> Typing.sendKeys(TestCalls.on(server, "pane_id", pane, "keys", List.of())));

        assertTrue(String.valueOf(refused.getMessage()).contains("C-c"), refused.getMessage());
    }

    @Test
    void pastedTextArrivesAsCharactersRatherThanKeyNames(Server server) {
        String pane = server.panes().get(0).id().value();

        Typing.Pasted pasted = Typing.pasteText(TestCalls.on(server, "pane_id", pane, "text", "Enter [C-c] done"));

        assertEquals(16, pasted.characters());
        assertTrue(String.valueOf(pasted.note()).contains("pass 'enter'"), String.valueOf(pasted.note()));
    }

    /** A signal outlives the moment it was sent, which is what draining exists to undo. */
    @Test
    void aChannelSignalledWithNobodyWaitingSatisfiesTheNextWait(Server server) {
        Channels.signal(TestCalls.on(server, "channel", "left-over"));

        Channels.Woke woke = Channels.waitFor(TestCalls.on(server, "channel", "left-over", "timeout", 5));

        assertEquals(WakeReason.SIGNALLED.name(), woke.outcome());
        assertTrue(String.valueOf(woke.note()).contains("drain_first"), String.valueOf(woke.note()));
    }

    @Test
    void drainingFirstMakesTheWaitStartFromAKnownState(Server server) {
        Channels.signal(TestCalls.on(server, "channel", "stale"));

        Channels.Drained drained = Channels.drain(TestCalls.on(server, "channel", "stale"));
        Channels.Woke woke = Channels.waitFor(TestCalls.on(server, "channel", "stale", "timeout", 1));

        assertTrue(drained.hadSignal());
        assertEquals(WakeReason.TIMED_OUT.name(), woke.outcome(), "the stale signal was consumed");
    }

    @Test
    void aWaitThatTimesOutSaysWhatToCheck(Server server) {
        Channels.Woke woke = Channels.waitFor(TestCalls.on(server, "channel", "never-signalled", "timeout", 1));

        assertEquals(WakeReason.TIMED_OUT.name(), woke.outcome());
        assertTrue(String.valueOf(woke.note()).contains("wait-for -S"), String.valueOf(woke.note()));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void optionsAreReadFromTheScopeThatWasAskedFor(Server server) {
        Settings.setOption(TestCalls.on(server, "scope", "global", "name", "@probe", "value", "set-here"));

        Settings.OptionValues read = Settings.showOptions(TestCalls.on(server, "scope", "global"));

        assertEquals("set-here", read.options().get("@probe"));
        assertEquals("global", read.scope());
    }

    @Test
    void aScopeNeedingATargetSaysSoRatherThanGuessing(Server server) {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> Settings.showOptions(TestCalls.on(server, "scope", "pane")));

        assertTrue(String.valueOf(refused.getMessage()).contains("'target'"), refused.getMessage());
    }

    @Test
    void hooksAreReadableAndSayWhyTheyAreNotWritable(Server server) {
        Settings.HookValues hooks = Settings.showHooks(TestCalls.on(server, "scope", "global"));

        assertTrue(hooks.note().contains("config file"), hooks.note());
    }

    @Test
    void aTargetThatIsNotThereNamesTheToolThatFindsOne(Server server) {
        ObjectDoesNotExist missing = assertThrows(ObjectDoesNotExist.class, () -> Targets.window(server, "@999"));

        assertTrue(String.valueOf(missing.getMessage()).contains("tmux_list_windows"), missing.getMessage());
    }

    /** tmux would read a bare number as an index, acting on a real but unintended pane. */
    @Test
    void aBareNumberIsRefusedAsAPaneId(Server server) {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Targets.pane(server, "1"));

        assertTrue(String.valueOf(refused.getMessage()).contains("%1"), refused.getMessage());
    }
}
