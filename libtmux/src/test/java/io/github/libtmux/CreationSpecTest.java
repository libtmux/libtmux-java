package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the window and session specs lower to, and what they refuse.
 *
 * <p>Neither command changed its flags between 3.2a and 3.7b, so the version rules here are about
 * behaviour tmux accepts and then ignores rather than about flags it rejects.
 */
final class CreationSpecTest {

    private static final TmuxVersion V32A = new TmuxVersion(3, 2, "a");
    private static final TmuxVersion V37B = new TmuxVersion(3, 7, "b");
    private static final String FORMAT = "#{window_id}";

    // ------------------------------------------------------------------------------ new-window

    @Test
    void aPlainWindowAsksForNothingBeyondTheWindow() {
        List<String> argv = WindowSpec.builder().build().argv("$1", FORMAT);

        assertEquals(List.of("new-window", "-t", "$1", "-P", "-F", FORMAT), argv);
    }

    /**
     * A window and a pane follow tmux and select what they made; a session cannot, because
     * new-session without -d attaches, and attaching needs a terminal a library rarely has.
     */
    @Test
    void onlyASessionIsDetachedByDefault() {
        assertFalse(WindowSpec.builder().build().argv("$1", FORMAT).contains("-d"));
        assertTrue(WindowSpec.builder().detached().build().argv("$1", FORMAT).contains("-d"));
        assertTrue(SessionSpec.builder().build().argv(FORMAT).contains("-d"));
    }

    @Test
    void placementIsAbsentUntilItIsAskedFor() {
        assertFalse(WindowSpec.builder().build().argv("$1", FORMAT).contains("-a"));
        assertTrue(WindowSpec.builder().after().build().argv("$1", FORMAT).contains("-a"));
        assertTrue(WindowSpec.builder().before().build().argv("$1", FORMAT).contains("-b"));
    }

    @Test
    void replacingAndReusingAreDifferentFlags() {
        assertTrue(WindowSpec.builder()
                .replaceExisting()
                .build()
                .argv("$1", FORMAT)
                .contains("-k"));
        assertTrue(
                WindowSpec.builder().reuseExisting().build().argv("$1", FORMAT).contains("-S"));
    }

    /** {@code -k} needs an index to replace; without one tmux picks a free one and destroys nothing. */
    @Test
    void anIndexTurnsTheTargetFromASessionIntoAWinlink() {
        List<String> plain = WindowSpec.builder().build().argv("$1", FORMAT);
        List<String> placed = WindowSpec.builder().atIndex(3).build().argv("$1", FORMAT);

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
                .argv("$1", FORMAT);

        assertEquals(List.of("journalctl", "-f"), argv.subList(argv.size() - 2, argv.size()));
    }

    @Test
    void aWindowStartDirectoryIsPassedToTmux() {
        List<String> argv = WindowSpec.builder().in(Path.of("/srv")).build().argv("$1", FORMAT);
        assertEquals("/srv", argv.get(argv.indexOf("-c") + 1));
    }

    /**
     * tmux 3.2a resolves a relative {@code -c} against the server's working directory, falling back
     * to home; 3.3 onwards resolves it against the requesting client's, which is this process's.
     * Every spawn site sends it resolved, so every release starts the process where 3.3 does.
     */
    @Test
    void aRelativeDirectoryReachesTmuxResolvedAgainstThisProcess() {
        Path relative = Path.of("sub");
        String resolved = relative.toAbsolutePath().toString();

        List<String> session = SessionSpec.builder().in(relative).build().argv(FORMAT);
        List<String> window = WindowSpec.builder().in(relative).build().argv("$1", FORMAT);
        List<String> split = SplitSpec.builder().in(relative).build().argv("%1", FORMAT, V32A);
        List<String> respawn = Pane.respawnArgv(new PaneId("%1"), relative);

        assertEquals(resolved, session.get(session.indexOf("-c") + 1));
        assertEquals(resolved, window.get(window.indexOf("-c") + 1));
        assertEquals(resolved, split.get(split.indexOf("-c") + 1));
        assertEquals(resolved, respawn.get(respawn.indexOf("-c") + 1));
    }

    @Test
    void aWindowWithoutADirectoryUsesTmuxDefaults() {
        assertDoesNotThrow(() -> WindowSpec.builder().named("plain").build().argv("$1", FORMAT));
    }

    // ----------------------------------------------------------------------------- new-session

    @Test
    void aPlainSessionIsAlwaysDetached() {
        List<String> argv = SessionSpec.builder().build().argv(FORMAT);

        assertEquals(List.of("new-session", "-d", "-P", "-F", FORMAT), argv);
    }

    @Test
    void aSizeBecomesTheTwoFlagsTmuxWants() {
        List<String> argv =
                SessionSpec.builder().sized(new Dimensions(120, 40)).build().argv(FORMAT);

        assertEquals("120", argv.get(argv.indexOf("-x") + 1));
        assertEquals("40", argv.get(argv.indexOf("-y") + 1));
    }

    @Test
    void clientFlagsGoOutAsOneCommaSeparatedValue() {
        List<String> argv = SessionSpec.builder()
                .clientFlags("no-detach-on-destroy", "active-pane")
                .build()
                .argv(FORMAT);

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
                .argv(FORMAT);

        assertEquals("build", argv.get(argv.indexOf("-s") + 1));
        assertEquals("editor", argv.get(argv.indexOf("-n") + 1));
        assertEquals("/srv/app", argv.get(argv.indexOf("-c") + 1));
        assertTrue(argv.contains("ALPHA=1"));
        assertTrue(argv.containsAll(List.of("-D", "-X")));
    }

    @Test
    void callerDirectoriesAreLiteralizedExactlyOnceAtEverySpawnBoundary() {
        Path supplied = Path.of("/srv/#one/##two");
        String literal = "/srv/##one/####two";

        List<String> session = SessionSpec.builder().in(supplied).build().argv(FORMAT);
        List<String> window = WindowSpec.builder().in(supplied).build().argv("$1", FORMAT);
        List<String> split = SplitSpec.builder().in(supplied).build().argv("%1", FORMAT, V37B);
        List<String> respawn = Pane.respawnArgv(new PaneId("%1"), supplied);

        assertEquals(literal, session.get(session.indexOf("-c") + 1));
        assertEquals(literal, window.get(window.indexOf("-c") + 1));
        assertEquals(literal, split.get(split.indexOf("-c") + 1));
        assertEquals(literal, respawn.get(respawn.indexOf("-c") + 1));
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

        assertEquals(window.argv("$1", FORMAT), window.argv("$1", FORMAT));
        assertEquals(session.argv(FORMAT), session.argv(FORMAT));
        assertEquals(
                "$2", window.argv("$2", FORMAT).get(window.argv("$2", FORMAT).indexOf("-t") + 1));
    }
}
