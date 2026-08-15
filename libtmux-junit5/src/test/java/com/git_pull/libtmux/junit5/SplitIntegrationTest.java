package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.SplitSpec;
import com.git_pull.libtmux.TmuxVersion;
import com.git_pull.libtmux.UnsupportedTmuxVersion;
import com.git_pull.libtmux.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Splitting against a real tmux, on whichever release the lane is running.
 *
 * <p>Every version-dependent case asserts in both branches. A test that skips below 3.7 would report
 * the same green whether the gate works or whether nothing was ever checked.
 */
@ExtendWith(TmuxExtension.class)
final class SplitIntegrationTest {

    private static final TmuxVersion PANE_EXTRAS_SINCE = new TmuxVersion(3, 7, "");

    // ------------------------------------------------------------------------------- placement

    @Test
    void theDefaultSplitPutsTheNewPaneBelow(Server server) {
        Pane original = onlyPane(server);

        Pane created = original.split();

        assertNotEquals(original.id(), created.id());
        assertEquals(2, created.window().panes().size());
        assertTrue(created.edges().bottom(), "the new pane took the lower half");
        assertTrue(original.refresh().edges().top(), "and the original kept the upper");
    }

    @Test
    void splittingToTheRightDividesTheWidthInstead(Server server) {
        Pane original = onlyPane(server);
        int width = original.size().width();

        Pane created = original.split(s -> s.toRight());

        assertTrue(created.edges().right());
        assertTrue(created.size().width() < width, "a horizontal split narrows both panes");
        assertEquals(original.size().height(), created.size().height(), "and leaves the height alone");
    }

    @Test
    void splittingToTheLeftPutsTheNewPaneFirst(Server server) {
        Pane created = onlyPane(server).split(s -> s.toLeft());

        assertTrue(created.edges().left());
        assertEquals(0, created.index(), "-b makes the new pane the first one");
    }

    // ------------------------------------------------------------------------------------ size

    @Test
    void aSizeInCellsIsTheSizeTheNewPaneGets(Server server) {
        Pane created = onlyPane(server).split(s -> s.cells(5));

        assertEquals(5, created.size().height());
    }

    /**
     * The percentage goes out as {@code -l N%}, never as {@code -p}: tmux 3.4 reads the {@code -l}
     * argument while handling {@code -p} and fails with {@code size missing}.
     */
    @Test
    void aPercentageWorksOnEveryReleaseIncludingTheOneWhereMinusPIsBroken(Server server) {
        Pane original = onlyPane(server);
        int height = original.size().height();

        Pane created = original.split(s -> s.percent(25));

        int quarter = height / 4;
        assertTrue(
                Math.abs(created.size().height() - quarter) <= 2,
                "asked for a quarter of " + height + ", got " + created.size().height());
    }

    // ----------------------------------------------------------------------------- what it runs

    @Test
    void aCommandAndAnEnvironmentAndADirectoryAllReachTheNewPane(Server server, @TempDir Path directory)
            throws InterruptedException {
        Path written = directory.resolve("seen");

        onlyPane(server)
                .split(s -> s.in(directory)
                        .env("LIBTMUX_PROBE", "carried")
                        .running("sh", "-c", "printf '%s' \"$LIBTMUX_PROBE\" > seen; sleep 30"));

        assertTrue(await(() -> Files.exists(written)), "the command never ran in the directory it was given");
        assertEquals("carried", read(written), "the pane did not inherit the variable");
    }

    @Test
    void aPaneRunningACommandReportsThatCommand(Server server) throws InterruptedException {
        Pane created = onlyPane(server).split(s -> s.running("sleep", "30"));

        assertTrue(
                await(() -> "sleep".equals(created.refresh().currentCommand())),
                "the pane never reported the command it was started with");
    }

    // ------------------------------------------------------------------------------ whole window

    @Test
    void aFullWindowSplitSpansTheWindowRatherThanTheTargetPane(Server server) {
        Window window = onlyPane(server).window();
        int width = window.size().width();
        Pane right = window.split(s -> s.toRight());

        Pane below = right.split(s -> s.below().fullWindow());

        assertEquals(width, below.size().width(), "-f divides the window, not the column");
    }

    @Test
    void aZoomedSplitArrivesZoomed(Server server) {
        Pane created = onlyPane(server).split(s -> s.zoomed());

        assertEquals(
                "1",
                server.cmd("display-message", "-p", "-t", created.id().value(), "#{window_zoomed_flag}")
                        .stdout()
                        .get(0));
    }

    // ------------------------------------------------------------------------------ reuse

    @Test
    void oneSpecDescribesTheSplitOfTwoDifferentPanes(Server server) {
        SplitSpec sidebar = SplitSpec.builder().toRight().percent(25).build();
        Window window = onlyPane(server).window();
        Pane second = window.split();

        Pane fromFirst = onlyPane(server, 0).split(sidebar);
        Pane fromSecond = second.refresh().split(sidebar);

        assertNotEquals(fromFirst.id(), fromSecond.id());
        assertTrue(fromFirst.edges().right());
        assertTrue(fromSecond.edges().right());
    }

    @Test
    void splittingAWindowAndSplittingItsActivePaneAgree(Server server) {
        Window window = onlyPane(server).window();

        Pane viaWindow = window.split(s -> s.toRight());

        assertEquals(2, window.refresh().panes().size());
        assertTrue(viaWindow.edges().right());
    }

    // ------------------------------------------------------------------------- the 3.7 options

    @Test
    void anEmptyPaneIsCreatedOrRefusedDependingOnTheRelease(Server server) {
        Pane original = onlyPane(server);

        if (server.version().atLeast(PANE_EXTRAS_SINCE)) {
            Pane created = original.split(s -> s.empty());

            assertEquals(2, created.window().panes().size(), "the pane exists");
            assertEquals(0, created.pid(), "and nothing is running in it");
        } else {
            UnsupportedTmuxVersion refused =
                    assertThrows(UnsupportedTmuxVersion.class, () -> original.split(s -> s.empty()));

            assertTrue(String.valueOf(refused.getMessage()).contains("an empty pane"));
            assertEquals(1, original.window().panes().size(), "and nothing was created");
        }
    }

    @Test
    void keepingAPaneAfterItsCommandExitsIsCreatedOrRefused(Server server) throws InterruptedException {
        Pane original = onlyPane(server);

        if (server.version().atLeast(PANE_EXTRAS_SINCE)) {
            Pane created = original.split(s -> s.keepOnExit().running("true"));

            assertTrue(
                    await(() -> created.window().panes().size() == 2),
                    "the pane closed even though it was asked to stay");
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> original.split(s -> s.keepOnExit()));
        }
    }

    @Test
    void styleOnSplitIsAppliedOrRefused(Server server) {
        Pane original = onlyPane(server);

        if (server.version().atLeast(PANE_EXTRAS_SINCE)) {
            Pane created = original.split(s -> s.style("fg=red"));

            assertTrue(server.cmd("show-options", "-p", "-t", created.id().value(), "window-style")
                    .stdout()
                    .get(0)
                    .contains("fg=red"));
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> original.split(s -> s.style("fg=red")));
        }
    }

    /**
     * A spec refused for its version must not have reached tmux.
     *
     * <p>Below 3.7 the refusal comes from this library; at 3.7 and above there is nothing to refuse,
     * so the case only says anything on the older lanes and says so out loud.
     */
    @Test
    void aSpecRefusedForItsVersionNeverReachesTmux(Server server) {
        Pane original = onlyPane(server);
        int before = original.window().panes().size();

        if (server.version().atLeast(PANE_EXTRAS_SINCE)) {
            assertEquals(1, before, "nothing to refuse on this release");
            return;
        }

        assertThrows(UnsupportedTmuxVersion.class, () -> original.split(s -> s.empty()));

        assertTrue(server.isAlive(), "a refusal is not a reason to lose the server");
        assertEquals(before, original.window().panes().size(), "no pane was created");
    }

    // ------------------------------------------------------------------------------- helpers

    private static Pane onlyPane(Server server) {
        return onlyPane(server, 0);
    }

    private static Pane onlyPane(Server server, int index) {
        List<Pane> panes = server.sessions().get(0).windows().get(0).panes();
        return panes.get(index);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError("could not read " + file, e);
        }
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
