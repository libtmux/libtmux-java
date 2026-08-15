package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Hooks;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Binding commands to tmux events, at each scope that holds them.
 *
 * <p>{@code set-hook} declares the same flags from 3.2a to 3.7b, so nothing here is version-gated.
 * What is easy to get wrong is the scope: a hook set where it does not live is accepted and dropped.
 */
@ExtendWith(TmuxExtension.class)
final class HookIntegrationTest {

    @Test
    void aHookSetOnceIsAListOfOne(Server server) {
        Hooks hooks = session(server).hooks();

        hooks.set("after-new-window", "display-message one");

        assertEquals(List.of("display-message one"), hooks.all().get("after-new-window"));
    }

    @Test
    void theEventIsTheKeyAndTheSubscriptIsTheOrder(Server server) {
        Hooks hooks = session(server).hooks();

        hooks.set("after-new-window", "display-message one");
        hooks.append("after-new-window", "display-message two");
        hooks.append("after-new-window", "display-message three");

        assertEquals(
                List.of("display-message one", "display-message two", "display-message three"),
                hooks.all().get("after-new-window"),
                "tmux runs them in subscript order, so that is the order they come back in");
        assertTrue(
                hooks.all().keySet().stream().noneMatch(event -> event.contains("[")),
                "a subscript is a position, not part of the name a caller looks up");
    }

    /** Setting without appending discards the whole array, which is tmux's behaviour, not a bug. */
    @Test
    void settingAgainReplacesEverythingBoundToTheEvent(Server server) {
        Hooks hooks = session(server).hooks();
        hooks.set("after-new-window", "display-message one");
        hooks.append("after-new-window", "display-message two");

        hooks.set("after-new-window", "display-message only");

        assertEquals(List.of("display-message only"), hooks.all().get("after-new-window"));
    }

    @Test
    void unsettingRemovesEveryCommandBoundToTheEvent(Server server) {
        Hooks hooks = session(server).hooks();
        hooks.set("after-new-window", "display-message one");
        hooks.append("after-new-window", "display-message two");

        hooks.unset("after-new-window");

        assertFalse(hooks.all().containsKey("after-new-window"));
    }

    @Test
    void aHookCanBeRunWithoutWaitingForItsEvent(Server server) {
        Session session = session(server);
        session.hooks().set("after-new-window", "rename-window ran-early");

        session.hooks().run("after-new-window");

        assertTrue(
                session.refresh().windows().stream().anyMatch(window -> "ran-early".equals(window.name())),
                "the hook did not run");
    }

    // ------------------------------------------------------------------------------------ scopes

    /**
     * A hook lives at a particular scope. {@code pane-focus-in} is a window hook and takes at window
     * scope; {@code alert-bell} is a session hook and is accepted there and then dropped, with no
     * error either way.
     */
    @Test
    void aHookSetWhereItDoesNotLiveIsSilentlyDiscarded(Server server) {
        Window window = session(server).windows().get(0);

        window.hooks().set("pane-focus-in", "display-message belongs-here");
        window.hooks().set("alert-bell", "display-message does-not");

        assertEquals(
                List.of("display-message belongs-here"),
                window.hooks().all().get("pane-focus-in"),
                "a window hook takes at window scope");
        assertFalse(
                window.hooks().all().containsKey("alert-bell"),
                "a session hook set at window scope is accepted and dropped");
    }

    @Test
    void eachScopeKeepsItsOwnHooks(Server server) {
        Session session = session(server);
        Window window = session.windows().get(0);

        session.hooks().set("after-new-window", "display-message session-level");
        window.hooks().set("window-renamed", "display-message window-level");

        assertTrue(session.hooks().all().containsKey("after-new-window"));
        assertFalse(session.hooks().all().containsKey("window-renamed"), "the window's hook is not the session's");
        assertTrue(window.hooks().all().containsKey("window-renamed"));
        assertFalse(window.hooks().all().containsKey("after-new-window"), "and the session's is not the window's");
    }

    @Test
    void aPaneScopeAcceptsThePaneHooks(Server server) {
        var pane = session(server).windows().get(0).panes().get(0);

        pane.hooks().set("pane-focus-in", "display-message pane-level");

        assertEquals(List.of("display-message pane-level"), pane.hooks().all().get("pane-focus-in"));
    }

    @Test
    void aScopeWithNothingBoundAnswersWithNothing(Server server) {
        assertTrue(session(server).windows().get(0).panes().get(0).hooks().all().isEmpty());
    }

    private static Session session(Server server) {
        return server.sessions().get(0);
    }
}
