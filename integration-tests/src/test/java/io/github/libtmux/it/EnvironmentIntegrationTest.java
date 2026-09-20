package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Environment;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The environment tmux hands to processes it starts.
 *
 * <p>Three states, and the point of the tests below is that they stay three. A name set to a value,
 * a name tmux is told to subtract from what a new process inherits, and a name it has never heard
 * of are different facts, and tmux reports them differently — {@code NAME=value}, {@code -NAME}, and
 * a failure saying the variable is unknown. Collapsing the middle one into "absent" would lose the
 * only way to see that something was deliberately taken away.
 */
@ExtendWith(TmuxExtension.class)
final class EnvironmentIntegrationTest {

    @Test
    void aValueSetOnTheServerIsReadBack(Server server) {
        Environment environment = server.environment();

        environment.set("LIBTMUX_TOKEN", "abc123");

        assertEquals(Optional.of("abc123"), environment.get("LIBTMUX_TOKEN"));
        assertEquals("abc123", environment.all().get("LIBTMUX_TOKEN"));
        assertFalse(environment.isRemoved("LIBTMUX_TOKEN"));
    }

    @Test
    void aNameTmuxHasNeverHeardOfIsSimplyAbsent(Server server) {
        Environment environment = server.environment();

        assertEquals(Optional.empty(), environment.get("LIBTMUX_NEVER_SET"));
        assertFalse(environment.isRemoved("LIBTMUX_NEVER_SET"));
    }

    @Test
    void aRemovedNameIsNotTheSameAsAnAbsentOne(Server server) {
        Environment environment = server.environment();

        environment.remove("LIBTMUX_SUBTRACTED");

        assertTrue(environment.isRemoved("LIBTMUX_SUBTRACTED"), "tmux prints it with a leading dash, not as missing");
        assertEquals(
                Optional.empty(), environment.get("LIBTMUX_SUBTRACTED"), "it has no value, because that is the point");
        assertTrue(environment.removed().contains("LIBTMUX_SUBTRACTED"));
        assertFalse(environment.all().containsKey("LIBTMUX_SUBTRACTED"), "a removal is not a value");
    }

    @Test
    void unsettingLeavesNeitherAValueNorAMark(Server server) {
        Environment environment = server.environment();
        environment.set("LIBTMUX_TEMPORARY", "here");

        environment.unset("LIBTMUX_TEMPORARY");

        assertEquals(Optional.empty(), environment.get("LIBTMUX_TEMPORARY"));
        assertFalse(environment.isRemoved("LIBTMUX_TEMPORARY"), "unset forgets the name; remove remembers it");
    }

    @Test
    void aFormatIsExpandedOnlyWhenAskedFor(Server server) {
        Environment environment = server.environment();

        environment.set("LIBTMUX_LITERAL", "#{session_name}");
        environment.setExpanded("LIBTMUX_EXPANDED", "#{session_name}");

        assertEquals(Optional.of("#{session_name}"), environment.get("LIBTMUX_LITERAL"));
        assertEquals(
                Optional.of(server.sessions().get(0).name()),
                environment.get("LIBTMUX_EXPANDED"),
                "setExpanded asks tmux to expand it once, at the time of the call");
    }

    @Test
    void aSessionKeepsItsOwnEnvironmentSeparateFromTheServers(Server server) {
        Session session = server.sessions().get(0);
        server.environment().set("LIBTMUX_SCOPE", "server");

        session.environment().set("LIBTMUX_SCOPE", "session");

        assertEquals(Optional.of("server"), server.environment().get("LIBTMUX_SCOPE"));
        assertEquals(Optional.of("session"), session.environment().get("LIBTMUX_SCOPE"));
    }

    /**
     * The defect this pins: a value holding a newline was read back three different wrong ways from
     * one perfectly stored value — truncated, as an invented variable, and as a removal this session
     * never asked for. Rare and real: a key or a credential is what a long-lived session refreshes.
     */
    @Test
    void aValueHoldingNewlinesIsReadBackWhole(Server server) {
        Environment environment = server.environment();
        String multiline = "first\nSECOND=injected\n-PATH";

        environment.set("KEY", multiline);

        assertEquals(Optional.of(multiline), environment.get("KEY"), "the value tmux stored is the value read back");
        assertFalse(environment.all().containsKey("SECOND"), "a line of the value is not a variable");
        assertFalse(environment.removed().contains("PATH"), "and it is not a removal either");
        assertFalse(environment.isRemoved("PATH"), "which all() and isRemoved must agree about");
        assertEquals(multiline, environment.all().get("KEY"), "the listing carries it whole too");
    }

    /** tmux escapes what its shell form would otherwise let a value say. */
    @Test
    void aValueCannotSayAnythingAboutOtherVariables(Server server) {
        Environment environment = server.environment();
        String hostile = "x\"; export EVIL; #";

        environment.set("TRICK", hostile);

        assertEquals(Optional.of(hostile), environment.get("TRICK"));
        assertFalse(environment.all().containsKey("EVIL"), "a value is data, whatever it looks like");
    }

    /**
     * Every character tmux escapes in its shell form comes back as itself.
     *
     * <p>Both shapes of {@code $} are here on purpose. tmux 3.4 writes a second backslash before a
     * {@code $} a name begins after, where every other supported release writes one, so {@code
     * $HOME} pins that the extra one is taken back off and {@code \\$HOME} pins that a backslash
     * the value really holds is not.
     */
    @Test
    void theCharactersTmuxEscapesSurviveTheRoundTrip(Server server) {
        Environment environment = server.environment();
        String awkward = "$HOME \\$HOME ${BRACED} $1 `date` \"quoted\" back\\slash trailing\\";

        environment.set("AWKWARD", awkward);

        assertEquals(Optional.of(awkward), environment.get("AWKWARD"));
        assertEquals(awkward, environment.all().get("AWKWARD"));
    }

    /**
     * What a pane opened later is handed is neither scope on its own: the session's environment lies
     * over the server's, and what the session marked as not to be inherited is taken out of both.
     */
    @Test
    void theEffectiveEnvironmentIsBothScopesTakenTogether(Server server) {
        Session session = server.sessions().get(0);
        server.environment().set("ONLY_GLOBAL", "from-server");
        server.environment().set("OVERRIDDEN", "from-server");
        server.environment().set("WITHHELD", "from-server");
        session.environment().set("OVERRIDDEN", "from-session");
        session.environment().set("ONLY_SESSION", "from-session");
        session.environment().remove("WITHHELD");

        Map<String, String> effective = session.environment().effective();

        assertEquals("from-server", effective.get("ONLY_GLOBAL"), "the server's own names carry through");
        assertEquals("from-session", effective.get("OVERRIDDEN"), "and the session's win where both have one");
        assertEquals("from-session", effective.get("ONLY_SESSION"));
        assertFalse(effective.containsKey("WITHHELD"), "a name the session withholds is not handed on");
        assertEquals(
                server.environment().all(),
                server.environment().effective(),
                "the server scope has nothing laid over it");
    }

    @Test
    void aNameTmuxCouldNotStoreIsRefusedBeforeItIsSent(Server server) {
        Environment environment = server.environment();

        // tmux splits a name from its value at the first '=', so a name containing one would set
        // something else entirely and report success.
        assertThrows(IllegalArgumentException.class, () -> environment.set("BAD=NAME", "x"));
        assertThrows(IllegalArgumentException.class, () -> environment.set("", "x"));
    }
}
