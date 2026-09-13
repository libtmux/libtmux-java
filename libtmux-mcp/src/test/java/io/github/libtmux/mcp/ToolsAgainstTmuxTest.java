package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ObjectDoesNotExistException;
import io.github.libtmux.Server;
import io.github.libtmux.WakeReason;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.Map;
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

    /** Ending a container that holds the caller's pane names the ones that could be ended instead. */
    @Test
    void refusingToEndAContainerNamesWhatCanBeEnded(Server server) {
        String mine = server.panes().get(0).id().value();
        String other = server.sessions().get(0).windows().get(0).split().id().value();
        String session = server.sessions().get(0).id().value();

        IllegalStateException refused = assertThrows(
                IllegalStateException.class, () -> Shaping.kill(TestCalls.asCaller(server, mine, "target", session)));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains(other), "the pane that could go is named: " + message);
        assertTrue(message.contains(mine), message);
        assertEquals(2, server.panes().size());
    }

    /** An empty listing has to say whether the server was empty or absent; a count cannot. */
    @Test
    void anEmptyListingSaysWhetherThereIsAServerAtAll(Server server) {
        Listings.Sessions running = Listings.sessions(server);
        server.killServer();
        Listings.Sessions gone = Listings.sessions(server);

        assertEquals(1, running.count());
        assertEquals(null, running.note(), "a listing that found something says nothing extra");
        assertEquals(0, gone.count());
        assertTrue(String.valueOf(gone.note()).contains("No tmux server is running"), String.valueOf(gone.note()));
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
    void validatedTmuxVariablesCanUseOnePaneContext(Server server) {
        String pane = server.panes().getFirst().id().value();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Operations.tmuxVariables(
                TestCalls.on(server, "names", List.of("pane_id", "session_name"), "pane", pane));
        @SuppressWarnings("unchecked")
        Map<String, String> values = (Map<String, String>) result.get("values");

        assertEquals(Map.of("pane_id", pane, "session_name", "libtmux"), values);
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
        assertTrue(message.contains(pane), "the refusal names the pane it protected: " + message);
        assertTrue(message.contains("confirm_self"), message);
        // The override is offered, but only after what to do instead — a model told to tidy up acts
        // on whichever it reads first, and one that read confirm_self first killed its own server.
        assertTrue(
                message.indexOf("instead") < message.indexOf("confirm_self"),
                "what can be ended safely has to come before the override: " + message);
        assertEquals(2, server.panes().size(), "and the pane is still there");
    }

    @Test
    void confirmingIsEnoughToEndTheCallersOwnPane(Server server) {
        String pane = server.sessions().get(0).windows().get(0).split().id().value();

        Shaping.kill(TestCalls.asCaller(server, pane, "target", pane, "confirm_self", true));

        assertEquals(1, server.panes().size());
    }

    @Test
    void uncertainCallerIdentityRefusesServerKill(Server server) {
        String pane = server.panes().get(0).id().value();
        Map<String, String> uncertain = Map.of(
                "TMUX", "/tmp/libtmux-java-test/missing-socket," + server.expand("#{pid}") + ",0", "TMUX_PANE", pane);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> Shaping.kill(TestCalls.withEnvironment(server, uncertain, "target", "server")));

        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("could not prove"), message);
        assertTrue(server.isAlive(), "uncertainty must not disable the destructive guard");
    }

    @Test
    void confirmationCannotOverrideUncertainCallerIdentity(Server server) {
        String pane = server.sessions().get(0).windows().get(0).split().id().value();
        String tmux = server.expand("#{socket_path},#{pid},0");
        List<Map<String, String>> uncertain = List.of(
                Map.of("TMUX", tmux),
                Map.of("TMUX_PANE", pane),
                Map.of("TMUX", "", "TMUX_PANE", pane),
                Map.of("TMUX", tmux, "TMUX_PANE", ""),
                Map.of("TMUX", "malformed", "TMUX_PANE", pane));

        for (Map<String, String> environment : uncertain) {
            assertThrows(
                    IllegalStateException.class,
                    () -> Shaping.kill(
                            TestCalls.withEnvironment(server, environment, "target", pane, "confirm_self", true)));
        }

        assertTrue(server.panes().stream()
                .anyMatch(candidate -> candidate.id().value().equals(pane)));
    }

    @Test
    void aCompleteForeignCallerDoesNotNeedSelfConfirmation(Server server) {
        String pane = server.sessions().get(0).windows().get(0).split().id().value();
        Map<String, String> foreign = Map.of("TMUX", "/dev/null,1,0", "TMUX_PANE", "%0");

        Shaping.kill(TestCalls.withEnvironment(server, foreign, "target", pane, "confirm_self", true));

        assertTrue(server.panes().stream()
                .noneMatch(candidate -> candidate.id().value().equals(pane)));
    }

    @Test
    void confirmationCannotOverrideAStaleCallerSession(Server server) {
        String pane = server.sessions().get(0).windows().get(0).split().id().value();
        Call confirmed = TestCalls.asCaller(server, pane, "target", pane, "confirm_self", true);
        String destination = server.newSession("moved-caller")
                .windows()
                .get(0)
                .panes()
                .get(0)
                .id()
                .value();
        server.cmd("move-pane", "-s", pane, "-t", destination);

        assertThrows(IllegalStateException.class, () -> Shaping.kill(confirmed));

        assertTrue(server.panes().stream()
                .anyMatch(candidate -> candidate.id().value().equals(pane)));
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

    @Test
    void aSessionNamedServerIsKilledByItsListedIdWithoutEndingTheServer(Server server) {
        var namedServer = server.newSession("server");

        Shaping.Ended ended =
                Shaping.kill(TestCalls.on(server, "target", namedServer.id().value()));

        assertEquals("session", ended.kind());
        assertTrue(server.isAlive(), "a session name must not become a request to kill the server");
        assertTrue(server.sessions().stream().noneMatch(session -> session.id().equals(namedServer.id())));
    }

    @Test
    void sessionTargetsUseTheirListedIdsEvenWhenTheNameLooksLikeAWindowId(Server server) {
        var ambiguous = server.newSession(server.windows().get(0).id().value());

        Shaping.Changed renamed =
                Shaping.rename(TestCalls.on(server, "target", ambiguous.id().value(), "name", "renamed-safely"));
        Shaping.Ended ended =
                Shaping.kill(TestCalls.on(server, "target", ambiguous.id().value()));

        assertEquals("session", renamed.kind());
        assertEquals("renamed-safely", renamed.what());
        assertEquals("session", ended.kind());
        assertTrue(server.isAlive());
        assertEquals(1, server.sessions().size());
    }

    @Test
    void theServerTargetMeansTheWholeServer(Server server) {
        Shaping.Ended ended = Shaping.kill(TestCalls.on(server, "target", "server"));

        assertEquals("server", ended.kind());
        assertEquals(false, server.isAlive());
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

    // ---------------------------------------------------------------- channels

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
        ObjectDoesNotExistException missing =
                assertThrows(ObjectDoesNotExistException.class, () -> Targets.window(server, "@999"));

        assertTrue(String.valueOf(missing.getMessage()).contains("list_windows"), missing.getMessage());
    }

    /** tmux would read a bare number as an index, acting on a real but unintended pane. */
    @Test
    void aBareNumberIsRefusedAsAPaneId(Server server) {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Targets.pane(server, "1"));

        assertTrue(String.valueOf(refused.getMessage()).contains("%1"), refused.getMessage());
    }

    /**
     * A variable removed from the environment is reported as removed, not left out.
     *
     * <p>tmux prints it as {@code -NAME} with no {@code =}, which a parser keeping only {@code
     * NAME=value} lines dropped without a word, so a removed variable read exactly like one never set.
     */
    @Test
    void aRemovedVariableIsReportedRatherThanDropped(Server server) {
        server.cmd("set-environment", "-g", "LIBTMUX_JAVA_KEPT", "a=b");
        server.cmd("set-environment", "-g", "-r", "LIBTMUX_JAVA_REMOVED");

        Settings.Environment global = Settings.environment(TestCalls.on(server));

        assertEquals("a=b", global.variables().get("LIBTMUX_JAVA_KEPT"), "only the first = separates");
        assertTrue(global.unset().contains("LIBTMUX_JAVA_REMOVED"), "tmux said so as -NAME: " + global.unset());
        assertFalse(global.variables().containsKey("LIBTMUX_JAVA_REMOVED"));
        assertFalse(global.variables().keySet().stream().anyMatch(key -> key.startsWith("-")));
    }
}
