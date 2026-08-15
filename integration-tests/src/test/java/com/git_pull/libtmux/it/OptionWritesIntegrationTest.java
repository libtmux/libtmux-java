package com.git_pull.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Options;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.junit5.TmuxExtension;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Writing options in the ways tmux offers beyond replacing a value outright.
 *
 * <p>{@code set-option} and {@code show-options} declare byte-identical flags from 3.2a to 3.7b, so
 * nothing here is version-gated. What varies between releases is how many options exist, which is
 * why no count below is asserted exactly.
 */
@ExtendWith(TmuxExtension.class)
final class OptionWritesIntegrationTest {

    @Test
    void appendingAddsToTheEndRatherThanReplacing(Server server) {
        Options options = session(server).options();
        options.set("status-left", "one");

        options.append("status-left", "-two");

        assertEquals(Optional.of("one-two"), options.get("status-left"));
    }

    @Test
    void appendingToSomethingThisScopeNeverSetSimplySetsIt(Server server) {
        Options options = session(server).options();

        options.append("status-left", "only");

        assertEquals(Optional.of("only"), options.get("status-left"));
    }

    /**
     * tmux reports the already-set case as an error. Declining to overwrite is the point of the
     * call, not a failure, so it comes back as a value.
     */
    @Test
    void settingOnlyIfAbsentDeclinesRatherThanFailing(Server server) {
        Options options = session(server).options();

        assertTrue(options.setIfAbsent("status-left", "first"), "nothing was set, so it was taken");
        assertFalse(options.setIfAbsent("status-left", "second"), "already set, so it was declined");
        assertEquals(Optional.of("first"), options.get("status-left"), "and the first value stands");
    }

    @Test
    void settingAnExpandedValueStoresWhatTheFormatCameTo(Server server) {
        Session session = session(server);
        Options options = session.options();

        options.setExpanded("status-left", "in #{session_name}");

        assertEquals(
                Optional.of("in " + session.name()),
                options.get("status-left"),
                "the format is expanded once, when set");
    }

    @Test
    void aValueWithNoFormatInItIsStoredUnchanged(Server server) {
        Options options = session(server).options();

        options.setExpanded("status-left", "plain text");

        assertEquals(Optional.of("plain text"), options.get("status-left"));
    }

    // ------------------------------------------------------------------------ narrow versus wide

    @Test
    void aScopeThatSetsNothingStillHasEverythingInEffect(Server server) {
        Options options = session(server).options();

        assertTrue(options.all().isEmpty(), "a fresh session sets nothing of its own");
        assertFalse(options.effective().isEmpty(), "but it acts on what it inherits");
        assertTrue(
                options.effective().containsKey("status-left"),
                options.effective().keySet().toString());
    }

    @Test
    void settingOneOptionPutsItInTheNarrowViewAsWell(Server server) {
        Options options = session(server).options();

        options.set("status-left", "mine");

        assertEquals("mine", options.all().get("status-left"), "the narrow view is what this scope sets");
        assertEquals("mine", options.effective().get("status-left"), "and the wide view agrees");
    }

    @Test
    void unsettingReturnsTheOptionToWhatItInherits(Server server) {
        Options options = session(server).options();
        String inherited = options.effective().get("status-left");
        options.set("status-left", "mine");

        options.unset("status-left");

        assertFalse(options.all().containsKey("status-left"), "this scope no longer sets it");
        assertEquals(inherited, options.effective().get("status-left"), "and it acts on the inherited value again");
    }

    /**
     * A wide listing prints {@code status-left*} for a value inherited rather than set here. The
     * star is tmux's way of saying which is which, and {@link Options#all()} already answers that,
     * so the name a caller looks up has to be the name they get back.
     */
    @Test
    void aWideListingIsKeyedByPlainOptionNames(Server server) {
        Options options = session(server).options();

        assertTrue(
                options.effective().keySet().stream().noneMatch(name -> name.endsWith("*")),
                "the inherited marker must not reach a caller");
        assertTrue(options.effective().containsKey("status-left"));
        assertTrue(
                options.effective().keySet().stream().anyMatch(name -> name.endsWith("]")),
                "an array entry keeps its subscript, which is not a marker");
    }

    /** Array options keep the subscript, which is what addresses the entry when setting it back. */
    @Test
    void anArrayEntryIsAddressedByItsSubscript(Server server) {
        Options options = server.globalOptions();

        options.set("command-alias[99]", "probe=display-message");

        assertEquals(Optional.of("probe=display-message"), options.get("command-alias[99]"));
    }

    private static Session session(Server server) {
        return server.sessions().get(0);
    }
}
