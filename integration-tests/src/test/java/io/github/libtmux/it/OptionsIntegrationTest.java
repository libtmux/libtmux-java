package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * A window option lookup never passes through a session tree, so a window linked into two
     * sessions cannot hold a different value in each. Window state is not per-session.
     */
    @Test
    void aLinkedWindowReadsOneSetOfOptionsThroughEitherSession(Server server) {
        Session origin = server.sessions().get(0);
        Session other = server.newSession("other");
        Window shared = origin.windows().get(0);
        shared.linkTo(other);

        shared.options().set("main-pane-width", "81");

        List<Window> links = server.windows().stream()
                .filter(window -> window.id().equals(shared.id()))
                .toList();
        assertEquals(2, links.size(), "the window is in both sessions");
        for (Window link : links) {
            assertEquals(Optional.of("81"), link.options().get("main-pane-width"));
        }
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

    /**
     * tmux escapes a listed value with {@code vis(3)} and changes its mind about which characters
     * that reaches across the supported range, so a listing reports the value itself rather than
     * whatever spelling this release chose for it.
     */
    @Test
    void aListedValueIsTheValueRatherThanTmuxsSpellingOfIt(Server server) {
        Map<String, String> written = new LinkedHashMap<>();
        written.put("@spaces", "[#S] and a space");
        written.put("@backslash", "back\\slash");
        written.put("@newline", "first\nsecond");
        written.put("@tab", "a\tb");
        written.put("@dquote", "has \"quotes\"");
        written.put("@empty", "");
        written.put("@tilde", "~");

        written.forEach(server.globalOptions()::set);

        Map<String, String> listed = server.globalOptions().all();
        written.forEach((name, value) -> {
            assertEquals(Optional.of(value), server.globalOptions().get(name), name);
            assertEquals(value, listed.get(name), name + " listed");
        });
    }

    /**
     * tmux packs a command into 16384 bytes and refuses a longer one, so a scope with enough
     * options cannot be read in one, and a listing that quietly stopped early would be worse than
     * one that failed.
     */
    @Test
    void aScopeWithMoreOptionsThanOneCommandCanCarryIsStillListedWhole(Server server, @TempDir Path directory)
            throws Exception {
        // A handle's scope is the tighter case: its batch travels inside the staleness guard.
        Session session = server.sessions().get(0);
        StringBuilder script = new StringBuilder();
        for (int index = 0; index < 400; index++) {
            script.append("set-option -t ")
                    .append(session.id().value())
                    .append(" @filler")
                    .append(index)
                    .append(" value")
                    .append(index)
                    .append('\n');
        }
        Path config = directory.resolve("options.conf");
        Files.writeString(config, script);
        server.sourceFile(config);

        Map<String, String> all = session.options().all();

        assertEquals("value399", all.get("@filler399"));
        assertEquals("value0", all.get("@filler0"));
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
