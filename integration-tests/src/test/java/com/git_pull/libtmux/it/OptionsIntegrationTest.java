package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.junit5.TmuxExtension;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Options and hooks, at each of the scopes tmux actually keeps them.
 *
 * <p>The same option name can exist on the server, a session, a window and a pane, so a scope is
 * chosen when the view is obtained. These cases check that writing one scope does not read back
 * from another.
 */
@ExtendWith(TmuxExtension.class)
final class OptionsIntegrationTest {

    @Test
    void aServerOptionRoundTrips(Server server) {
        server.options().set("escape-time", "120");

        assertEquals(Optional.of("120"), server.options().get("escape-time"));
    }

    @Test
    void aSessionOptionRoundTrips(Server server) {
        Session session = server.sessions().get(0);

        session.options().set("base-index", "1");

        assertEquals(Optional.of("1"), session.options().get("base-index"));
    }

    @Test
    void aWindowOptionRoundTrips(Server server) {
        Window window = server.sessions().get(0).windows().get(0);

        window.options().set("main-pane-width", "88");

        assertEquals(Optional.of("88"), window.options().get("main-pane-width"));
    }

    @Test
    void aPaneOptionRoundTrips(Server server) {
        Pane pane = server.sessions().get(0).windows().get(0).panes().get(0);

        pane.options().set("allow-rename", "on");

        assertEquals(Optional.of("on"), pane.options().get("allow-rename"));
    }

    /** The reason a scope is fixed when the view is obtained rather than passed to each call. */
    @Test
    void oneScopeDoesNotAnswerForAnother(Server server) {
        Session session = server.sessions().get(0);
        server.globalOptions().set("base-index", "0");

        session.options().set("base-index", "7");

        assertEquals(Optional.of("7"), session.options().get("base-index"));
        assertEquals(Optional.of("0"), server.globalOptions().get("base-index"));
    }

    @Test
    void unsettingFallsBackToWhatIsInherited(Server server) {
        Session session = server.sessions().get(0);
        server.globalOptions().set("base-index", "3");
        session.options().set("base-index", "9");

        session.options().unset("base-index");

        assertEquals(Optional.of("3"), session.options().get("base-index"), "the session inherits again");
        assertFalse(
                session.options().all().containsKey("base-index"),
                "and the narrower question, whether this scope sets it, now answers no");
    }

    /** An inherited value is what tmux acts on, so it is what get reports. */
    @Test
    void aScopeReportsWhatIsInEffectNotOnlyWhatItSets(Server server) {
        Session session = server.sessions().get(0);
        server.globalOptions().set("base-index", "3");

        assertEquals(Optional.of("3"), session.options().get("base-index"));
        assertFalse(session.options().all().containsKey("base-index"), "the session sets nothing itself");
        assertEquals(3, session.newWindow("proof").index().value(), "tmux really did act on the inherited value");
    }

    @Test
    void anOptionTmuxDoesNotKnowIsAbsentRatherThanEmpty(Server server) {
        assertEquals(Optional.empty(), server.options().get("no-such-option-exists"));
    }

    @Test
    void listingAScopeReturnsWhatTmuxPrinted(Server server) {
        server.options().set("escape-time", "77");

        Map<String, String> all = server.options().all();

        assertEquals("77", all.get("escape-time"));
        assertTrue(all.size() > 1, "a server has many options");
        assertTrue(
                all.keySet().stream().anyMatch(key -> key.contains("[")),
                "an array option keeps the subscript that addresses it");
    }

    @Test
    void aValueWithSpacesSurvivesTheRoundTrip(Server server) {
        server.globalOptions().set("status-left", "[#S] and a space");

        assertEquals(Optional.of("[#S] and a space"), server.globalOptions().get("status-left"));
        assertEquals("[#S] and a space", server.globalOptions().all().get("status-left"), "tmux quotes it, we do not");
    }

    @Test
    void aHookBindsToAnEventAndCanBeRemoved(Server server) {
        Session session = server.sessions().get(0);

        session.hooks().set("after-new-window", "display-message hooked");

        assertTrue(session.hooks().all().containsKey("after-new-window"), "the hook is bound");

        session.hooks().unset("after-new-window");

        assertFalse(session.hooks().all().containsKey("after-new-window"), "the hook is gone");
    }

    @Test
    void aHookActuallyRuns(Server server) {
        Session session = server.sessions().get(0);
        session.hooks().set("after-new-window", "rename-window hooked-ran");

        Window created = session.newWindow("before-hook");

        assertNotEquals("before-hook", created.refresh().name(), "tmux ran the hook after creating the window");
        assertEquals("hooked-ran", created.refresh().name());
    }
}
