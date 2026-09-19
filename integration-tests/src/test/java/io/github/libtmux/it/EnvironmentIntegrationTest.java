package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Environment;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.junit5.TmuxExtension;
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

    @Test
    void aNameTmuxCouldNotStoreIsRefusedBeforeItIsSent(Server server) {
        Environment environment = server.environment();

        // tmux splits a name from its value at the first '=', so a name containing one would set
        // something else entirely and report success.
        assertThrows(IllegalArgumentException.class, () -> environment.set("BAD=NAME", "x"));
        assertThrows(IllegalArgumentException.class, () -> environment.set("", "x"));
    }
}
