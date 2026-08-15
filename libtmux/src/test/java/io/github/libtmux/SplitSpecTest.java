package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a spec lowers to, and what it refuses to lower to.
 *
 * <p>Nothing here needs a server. A spec is a value, so the argv it produces and the version rules
 * it enforces are both decidable without tmux — which is the point of separating the description
 * from the call that applies it.
 */
final class SplitSpecTest {

    private static final TmuxVersion V36 = new TmuxVersion(3, 6, "");
    private static final TmuxVersion V37 = new TmuxVersion(3, 7, "");
    private static final String FORMAT = "#{pane_id}";

    private static List<String> argv(SplitSpec spec, TmuxVersion running) {
        return spec.argv("%3", FORMAT, running);
    }

    // ------------------------------------------------------------------------------ defaults

    /** tmux moves to the pane it just made, and so does this unless asked otherwise. */
    @Test
    void theDefaultSplitAsksForNothingBeyondAVerticalSplit() {
        List<String> argv = argv(SplitSpec.builder().build(), V36);

        assertEquals(List.of("split-window", "-v", "-t", "%3", "-P", "-F", FORMAT), argv);
    }

    @Test
    void detachedIsTheOptInBecauseTmuxSelectsByDefault() {
        assertFalse(argv(SplitSpec.builder().build(), V36).contains("-d"));
        assertTrue(argv(SplitSpec.builder().detached().build(), V36).contains("-d"));
    }

    // ------------------------------------------------------------------------------ direction

    @Test
    void aDirectionIsAnAxisAndWhetherTheNewPaneComesFirst() {
        assertEquals(List.of("-v"), flagsOf(SplitSpec.builder().below().build()));
        assertEquals(List.of("-v", "-b"), flagsOf(SplitSpec.builder().above().build()));
        assertEquals(List.of("-h"), flagsOf(SplitSpec.builder().toRight().build()));
        assertEquals(List.of("-h", "-b"), flagsOf(SplitSpec.builder().toLeft().build()));
    }

    private static List<String> flagsOf(SplitSpec spec) {
        List<String> argv = argv(spec, V36);
        return argv.subList(1, argv.indexOf("-t"));
    }

    // ----------------------------------------------------------------------------------- size

    /**
     * tmux's own {@code -p} is never emitted: 3.4 reads the {@code -l} argument while handling it and
     * fails with {@code size missing}. {@code -l N%} produces the same pane on every lane.
     */
    @Test
    void aPercentageIsSpelledAsASizeSoThatMinusPIsNeverNeeded() {
        List<String> argv = argv(SplitSpec.builder().percent(25).build(), V36);

        assertEquals("25%", argv.get(argv.indexOf("-l") + 1));
        assertFalse(argv.contains("-p"), "-p is broken in tmux 3.4");
    }

    @Test
    void cellsGoThroughTheSameFlagAsAPercentage() {
        List<String> argv = argv(SplitSpec.builder().cells(5).build(), V36);

        assertEquals("5", argv.get(argv.indexOf("-l") + 1));
    }

    @Test
    void aSizeAndAPercentageCannotBothSurviveBecauseThereIsOnlyOneField() {
        List<String> argv = argv(SplitSpec.builder().cells(5).percent(25).build(), V36);

        assertEquals(1, argv.stream().filter("-l"::equals).count());
        assertEquals("25%", argv.get(argv.indexOf("-l") + 1), "the last one asked for wins");
    }

    @Test
    void anImpossibleSizeIsRefusedWhereItIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> PaneSize.percent(0));
        assertThrows(IllegalArgumentException.class, () -> PaneSize.percent(101));
        assertThrows(IllegalArgumentException.class, () -> PaneSize.cells(0));
    }

    // ---------------------------------------------------------------------------------- start

    @Test
    void aCommandIsLastBecauseEverythingAfterItBelongsToIt() {
        List<String> argv = argv(SplitSpec.builder().running("htop", "-d", "5").build(), V36);

        assertEquals(List.of("htop", "-d", "5"), argv.subList(argv.size() - 3, argv.size()));
    }

    @Test
    void anEmptyPaneCarriesNoCommandAtAll() {
        List<String> argv = argv(SplitSpec.builder().empty().build(), V37);

        assertTrue(argv.contains("-E"));
        assertEquals(FORMAT, argv.get(argv.size() - 1), "nothing follows the format");
    }

    @Test
    void choosingEmptyAfterACommandKeepsOnlyTheEmptiness() {
        List<String> argv = argv(SplitSpec.builder().running("htop").empty().build(), V37);

        assertTrue(argv.contains("-E"));
        assertFalse(argv.contains("htop"), "tmux refuses a command on an empty pane");
    }

    @Test
    void anEmptyCommandIsRefusedWhereItIsWritten() {
        assertThrows(IllegalArgumentException.class, PaneStart::command);
    }

    // ------------------------------------------------------------------------------ passthrough

    @Test
    void aDirectoryAndAnEnvironmentReachTmuxOneFlagEach() {
        SplitSpec spec = SplitSpec.builder()
                .in(Path.of("/srv/app"))
                .env("ALPHA", "1")
                .environment(Map.of("BETA", "2"))
                .build();

        List<String> argv = argv(spec, V36);

        assertEquals("/srv/app", argv.get(argv.indexOf("-c") + 1));
        assertTrue(argv.containsAll(List.of("ALPHA=1", "BETA=2")));
        assertEquals(2, argv.stream().filter("-e"::equals).count());
    }

    @Test
    void theEnvironmentKeepsTheOrderItWasGivenIn() {
        SplitSpec spec = SplitSpec.builder()
                .env("FIRST", "1")
                .env("SECOND", "2")
                .env("THIRD", "3")
                .build();

        assertEquals(
                List.of("FIRST=1", "SECOND=2", "THIRD=3"),
                argv(spec, V36).stream().filter(a -> a.contains("=")).toList());
    }

    @Test
    void fullWindowAndZoomAreFlagsOfTheirOwn() {
        List<String> argv = argv(SplitSpec.builder().fullWindow().zoomed().build(), V36);

        assertTrue(argv.containsAll(List.of("-f", "-Z")));
    }

    // --------------------------------------------------------------------------- version rules

    @Test
    void theThreeSevenOptionsAreRefusedBeforeTmuxIsAsked() {
        assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().empty().build(), V36));
        assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().keepOnExit().build(), V36));
        assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().keepOnExit("done").build(), V36));
        assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().style("fg=red").build(), V36));
        assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().activeBorderStyle("fg=red").build(), V36));
        assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().inactiveBorderStyle("fg=red").build(), V36));
    }

    @Test
    void theRefusalNamesTheFeatureAndBothVersions() {
        UnsupportedTmuxVersion refused = assertThrows(
                UnsupportedTmuxVersion.class,
                () -> argv(SplitSpec.builder().empty().build(), new TmuxVersion(3, 2, "a")));

        assertEquals("an empty pane requires tmux 3.7, but this server runs 3.2a", refused.getMessage());
    }

    @Test
    void aSpecUsingNoThreeSevenOptionsIsFineOnEveryRelease() {
        SplitSpec everyday = SplitSpec.builder()
                .toRight()
                .percent(30)
                .running("htop")
                .in(Path.of("/tmp"))
                .zoomed()
                .build();

        assertFalse(argv(everyday, new TmuxVersion(3, 2, "a")).isEmpty());
    }

    /** A 3.7 patch release still has what 3.7 had; letter ordering is the trap this guards. */
    @Test
    void aPatchReleaseCountsAsHavingWhatItsBaseHad() {
        assertFalse(argv(SplitSpec.builder().empty().build(), new TmuxVersion(3, 7, "b"))
                .isEmpty());
    }

    @Test
    void anExitMessageImpliesKeepingRatherThanSayingItTwice() {
        List<String> argv = argv(SplitSpec.builder().keepOnExit("finished").build(), V37);

        assertEquals("finished", argv.get(argv.indexOf("-m") + 1));
        assertFalse(argv.contains("-k"), "-m already sets remain-on-exit");
    }

    // ---------------------------------------------------------------------------------- value

    @Test
    void aSpecIsAValueThatCanBeLoweredTwice() {
        SplitSpec sidebar = SplitSpec.builder().toRight().percent(25).build();

        assertEquals(argv(sidebar, V36), argv(sidebar, V36));

        List<String> elsewhere = sidebar.argv("%9", FORMAT, V36);
        assertEquals("%9", elsewhere.get(elsewhere.indexOf("-t") + 1), "the same spec, a different pane");
    }

    @Test
    void aSpecReportsWhatItWasAskedFor() {
        SplitSpec spec = SplitSpec.builder().toRight().percent(30).build();

        assertEquals(SplitDirection.RIGHT, spec.direction());
        assertEquals(PaneSize.percent(30), spec.size().orElseThrow());
        assertTrue(spec.directory().isEmpty());
        assertFalse(spec.detached(), "tmux moves to the pane it made, and nothing here asked it not to");
    }

    @Test
    void theEnvironmentHandedBackCannotBeChanged() {
        SplitSpec spec = SplitSpec.builder().env("A", "1").build();

        assertThrows(
                UnsupportedOperationException.class, () -> spec.environment().put("B", "2"));
    }
}
