package com.git_pull.libtmux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What the window and session specs lower to, and what they refuse.
 *
 * <p>Neither command changed its flags between 3.2a and 3.7b, so the version rules here are about
 * behaviour tmux accepts and then ignores rather than about flags it rejects.
 */
final class CreationSpecTest {

    private static final TmuxVersion V32A = new TmuxVersion(3, 2, "a");
    private static final TmuxVersion V33A = new TmuxVersion(3, 3, "a");
    private static final TmuxVersion V37B = new TmuxVersion(3, 7, "b");
    private static final String FORMAT = "#{window_id}";

    // ------------------------------------------------------------------------------ new-window

    @Test
    void aPlainWindowAsksForNothingBeyondTheWindow() {
        List<String> argv = WindowSpec.builder().build().argv("$1", FORMAT, V37B);

        assertEquals(List.of("new-window", "-t", "$1", "-P", "-F", FORMAT), argv);
    }

    /**
     * A window and a pane follow tmux and select what they made; a session cannot, because
     * new-session without -d attaches, and attaching needs a terminal a library rarely has.
     */
    @Test
    void onlyASessionIsDetachedByDefault() {
        assertFalse(WindowSpec.builder().build().argv("$1", FORMAT, V37B).contains("-d"));
        assertTrue(
                WindowSpec.builder().detached().build().argv("$1", FORMAT, V37B).contains("-d"));
        assertTrue(SessionSpec.builder().build().argv(FORMAT, () -> V37B).contains("-d"));
    }

    @Test
    void placementIsAbsentUntilItIsAskedFor() {
        assertFalse(WindowSpec.builder().build().argv("$1", FORMAT, V37B).contains("-a"));
        assertTrue(WindowSpec.builder().after().build().argv("$1", FORMAT, V37B).contains("-a"));
        assertTrue(
                WindowSpec.builder().before().build().argv("$1", FORMAT, V37B).contains("-b"));
    }

    @Test
    void replacingAndReusingAreDifferentFlags() {
        assertTrue(WindowSpec.builder()
                .replaceExisting()
                .build()
                .argv("$1", FORMAT, V37B)
                .contains("-k"));
        assertTrue(WindowSpec.builder()
                .reuseExisting()
                .build()
                .argv("$1", FORMAT, V37B)
                .contains("-S"));
    }

    /** {@code -k} needs an index to replace; without one tmux picks a free one and destroys nothing. */
    @Test
    void anIndexTurnsTheTargetFromASessionIntoAWinlink() {
        List<String> plain = WindowSpec.builder().build().argv("$1", FORMAT, V37B);
        List<String> placed = WindowSpec.builder().atIndex(3).build().argv("$1", FORMAT, V37B);

        assertEquals("$1", plain.get(plain.indexOf("-t") + 1));
        assertEquals("$1:3", placed.get(placed.indexOf("-t") + 1));
    }

    @Test
    void aNegativeIndexIsRefusedWhereItIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> WindowSpec.builder().atIndex(-1));
    }

    @Test
    void aWindowCommandIsLastBecauseEverythingAfterItBelongsToIt() {
        List<String> argv = WindowSpec.builder()
                .named("logs")
                .running("journalctl", "-f")
                .build()
                .argv("$1", FORMAT, V37B);

        assertEquals(List.of("journalctl", "-f"), argv.subList(argv.size() - 2, argv.size()));
    }

    /**
     * 3.2a takes {@code -c} on new-window and drops it, while honouring the same flag on
     * split-window. Nothing in the exit status says so, which is why this is refused rather than
     * sent.
     */
    @Test
    void aStartDirectoryForAWindowIsRefusedOnTheReleaseThatIgnoresIt() {
        WindowSpec spec = WindowSpec.builder().in(Path.of("/srv")).build();

        UnsupportedTmuxVersion refused =
                assertThrows(UnsupportedTmuxVersion.class, () -> spec.argv("$1", FORMAT, V32A));

        assertEquals(
                "a start directory for a new window requires tmux 3.3a, but this server runs 3.2a",
                refused.getMessage());
        assertDoesNotThrow(() -> spec.argv("$1", FORMAT, V33A));
    }

    @Test
    void aWindowWithoutADirectoryIsFineOnEveryRelease() {
        assertDoesNotThrow(() -> WindowSpec.builder().named("plain").build().argv("$1", FORMAT, V32A));
    }

    // ----------------------------------------------------------------------------- new-session

    @Test
    void aPlainSessionIsAlwaysDetached() {
        List<String> argv = SessionSpec.builder().build().argv(FORMAT, () -> V37B);

        assertEquals(List.of("new-session", "-d", "-P", "-F", FORMAT), argv);
    }

    @Test
    void aSizeBecomesTheTwoFlagsTmuxWants() {
        List<String> argv =
                SessionSpec.builder().sized(new Dimensions(120, 40)).build().argv(FORMAT, () -> V37B);

        assertEquals("120", argv.get(argv.indexOf("-x") + 1));
        assertEquals("40", argv.get(argv.indexOf("-y") + 1));
    }

    /** 3.2a accepts {@code -x}/{@code -y} for a detached session and gives it the default size. */
    @Test
    void aSizeIsRefusedOnTheReleaseThatIgnoresIt() {
        SessionSpec spec = SessionSpec.builder().sized(new Dimensions(120, 40)).build();

        UnsupportedTmuxVersion refused =
                assertThrows(UnsupportedTmuxVersion.class, () -> spec.argv(FORMAT, () -> V32A));

        assertEquals(
                "a size for a detached session requires tmux 3.3a, but this server runs 3.2a", refused.getMessage());
    }

    /**
     * Creating the first session on a socket is the one call that runs before there is a server, and
     * a socket with nothing behind it cannot answer what version it is. So nothing asks unless the
     * spec depends on the answer.
     */
    @Test
    void theVersionIsNotAskedForUnlessTheSpecNeedsIt() {
        AtomicInteger asked = new AtomicInteger();

        SessionSpec.builder().named("bootstrap").build().argv(FORMAT, () -> {
            asked.incrementAndGet();
            return V37B;
        });

        assertEquals(0, asked.get(), "a plain session must be creatable before a server exists");

        SessionSpec.builder().sized(new Dimensions(80, 24)).build().argv(FORMAT, () -> {
            asked.incrementAndGet();
            return V37B;
        });

        assertEquals(1, asked.get(), "a sized session does depend on the version");
    }

    /** tmux reads {@code -f} as one comma-separated list, not as a flag that may repeat. */
    @Test
    void clientFlagsGoOutAsOneCommaSeparatedValue() {
        List<String> argv = SessionSpec.builder()
                .clientFlags("no-detach-on-destroy", "active-pane")
                .build()
                .argv(FORMAT, () -> V37B);

        assertEquals(1, argv.stream().filter("-f"::equals).count());
        assertEquals("no-detach-on-destroy,active-pane", argv.get(argv.indexOf("-f") + 1));
    }

    @Test
    void aSessionCarriesItsDirectoryEnvironmentAndFirstWindowName() {
        List<String> argv = SessionSpec.builder()
                .named("build")
                .firstWindowNamed("editor")
                .in(Path.of("/srv/app"))
                .env("ALPHA", "1")
                .detachOthers()
                .withoutSize()
                .build()
                .argv(FORMAT, () -> V37B);

        assertEquals("build", argv.get(argv.indexOf("-s") + 1));
        assertEquals("editor", argv.get(argv.indexOf("-n") + 1));
        assertEquals("/srv/app", argv.get(argv.indexOf("-c") + 1));
        assertTrue(argv.contains("ALPHA=1"));
        assertTrue(argv.containsAll(List.of("-D", "-X")));
    }

    @Test
    void anEmptyCommandIsRefusedWhereItIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> WindowSpec.builder().running());
        assertThrows(IllegalArgumentException.class, () -> SessionSpec.builder().running());
    }

    @Test
    void bothSpecsAreDescriptionsThatCanBeLoweredTwice() {
        WindowSpec window = WindowSpec.builder().named("shared").build();
        SessionSpec session = SessionSpec.builder().named("shared").build();

        assertEquals(window.argv("$1", FORMAT, V37B), window.argv("$1", FORMAT, V37B));
        assertEquals(session.argv(FORMAT, () -> V37B), session.argv(FORMAT, () -> V37B));
        assertEquals(
                "$2",
                window.argv("$2", FORMAT, V37B)
                        .get(window.argv("$2", FORMAT, V37B).indexOf("-t") + 1));
    }
}
