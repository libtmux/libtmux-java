package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.CaptureSpec;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.UnsupportedTmuxVersion;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Reading a pane, including the part that has scrolled off it.
 *
 * <p>The pane runs the generator as its command rather than being typed into: a pane's shell is not
 * ready the moment the pane exists, and keys sent before it is are lost. Every count below therefore
 * describes tmux and not a race.
 *
 * <p>Sizes are asserted in relative terms because tmux 3.2a ignores {@code -x}/{@code -y} on a
 * detached session, so a pane there is the default size and not the one asked for.
 */
@ExtendWith(TmuxExtension.class)
final class CaptureIntegrationTest {

    private static final TmuxVersion TRIM_SINCE = new TmuxVersion(3, 4, "");
    private static final TmuxVersion MODE_SCREEN_SINCE = new TmuxVersion(3, 6, "");
    private static final TmuxVersion HYPERLINKS_SINCE = new TmuxVersion(3, 7, "");

    /** Twelve numbered lines, printed by the pane's own command, then held open. */
    private static Pane generated(Server server) throws InterruptedException {
        Session session = server.sessions().get(0);
        Pane pane = session.windows()
                .get(0)
                .split(s ->
                        s.running("sh", "-c", "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do echo line-$i; done; sleep 60"));
        assertTrue(
                await(() ->
                        pane.capture(c -> c.fromStartOfHistory()).stream().anyMatch(line -> line.contains("line-12"))),
                "the pane never printed what it was told to");
        return pane;
    }

    @Test
    void theWholeHistoryHoldsEveryLineThePanePrinted(Server server) throws Exception {
        Pane pane = generated(server);

        List<String> everything = pane.capture(c -> c.fromStartOfHistory());

        for (int i = 1; i <= 12; i++) {
            int line = i;
            assertTrue(
                    everything.stream().anyMatch(text -> text.contains("line-" + line)),
                    "line-" + line + " is missing from the history");
        }
    }

    @Test
    void aPlainCaptureNeverReachesFurtherThanTheHistoryDoes(Server server) throws Exception {
        Pane pane = generated(server);

        int visible = pane.capture().size();
        int everything = pane.capture(c -> c.fromStartOfHistory()).size();

        assertTrue(visible <= everything, "the visible area cannot hold more than the whole history");
    }

    @Test
    void aRangeReadsTheLinesBetweenItsEnds(Server server) throws Exception {
        Pane pane = generated(server);

        List<String> firstTwo = pane.capture(c -> c.from(0).to(1));

        assertEquals(2, firstTwo.size(), "a range of two lines is two lines: " + firstTwo);
    }

    @Test
    void goingBackFromTheTopReachesIntoTheScrollback(Server server) throws Exception {
        Pane pane = generated(server);

        int visible = pane.capture().size();
        int withHistory = pane.capture(c -> c.from(-3)).size();

        assertTrue(withHistory >= visible, "asking for earlier lines returned fewer: " + withHistory + " < " + visible);
    }

    @Test
    void aSpecIsADescriptionThatCanBeUsedOnMoreThanOnePane(Server server) throws Exception {
        CaptureSpec whole = CaptureSpec.builder().fromStartOfHistory().build();
        Pane first = generated(server);
        Pane second = generated(server);

        assertTrue(first.capture(whole).stream().anyMatch(line -> line.contains("line-12")));
        assertTrue(second.capture(whole).stream().anyMatch(line -> line.contains("line-12")));
    }

    /**
     * The contract is a difference between two reads, not a property of one. Whether any line
     * happens to carry trailing space depends on the pane's width and what padded it, which varies
     * by release; what does not vary is that a plain read drops the spaces and this one keeps them.
     */
    @Test
    void preservingTrailingSpaceKeepsWhatAPlainReadDrops(Server server) throws Exception {
        Session session = server.sessions().get(0);
        Pane pane = session.windows().get(0).split(s -> s.running("sh", "-c", "printf 'padded   \\n'; sleep 60"));
        assertTrue(
                await(() -> pane.capture().stream().anyMatch(line -> line.contains("padded"))),
                "the pane never printed the padded line");

        String plain = lineWith(pane.capture(), "padded");
        String preserved = lineWith(pane.capture(c -> c.preservingTrailingSpace()), "padded");

        assertEquals("padded", plain, "a plain read drops the trailing space");
        assertTrue(preserved.startsWith("padded "), "the preserved read kept none of it: [" + preserved + "]");
        assertTrue(preserved.length() > plain.length(), "preserving must keep more than dropping");
    }

    private static String lineWith(List<String> captured, String text) {
        return captured.stream()
                .filter(line -> line.contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line holds " + text + ": " + captured));
    }

    // ------------------------------------------------------------------------------ version rules

    @Test
    void trimmingIsHonouredOrRefusedDependingOnTheRelease(Server server) throws Exception {
        Pane pane = generated(server);

        if (server.version().atLeast(TRIM_SINCE)) {
            assertTrue(
                    pane.capture(c -> c.trimmingTrailingSpace()).stream().anyMatch(line -> line.contains("line-12")));
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.capture(c -> c.trimmingTrailingSpace()));
        }
    }

    @Test
    void theModeScreenIsHonouredOrRefusedDependingOnTheRelease(Server server) throws Exception {
        Pane pane = generated(server);

        if (server.version().atLeast(MODE_SCREEN_SINCE)) {
            pane.copyMode();
            assertTrue(pane.capture(c -> c.fromModeScreen()) != null);
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.capture(c -> c.fromModeScreen()));
        }
    }

    @Test
    void hyperlinksAndLineNumbersAreHonouredOrRefusedDependingOnTheRelease(Server server) throws Exception {
        Pane pane = generated(server);

        if (server.version().atLeast(HYPERLINKS_SINCE)) {
            assertTrue(pane.capture(c -> c.withLineNumbers()).stream().anyMatch(line -> line.contains("line-12")));
            assertTrue(pane.capture(c -> c.withHyperlinks()) != null);
        } else {
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.capture(c -> c.withLineNumbers()));
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.capture(c -> c.withHyperlinks()));
        }
    }

    /** A refused spec must not have read anything, on any release. */
    @Test
    void aRefusalReadsNothingAndLeavesThePaneAlone(Server server) throws Exception {
        Pane pane = generated(server);
        int before = pane.capture().size();

        if (!server.version().atLeast(HYPERLINKS_SINCE)) {
            assertThrows(UnsupportedTmuxVersion.class, () -> pane.capture(c -> c.withHyperlinks()));
        }

        assertEquals(before, pane.capture().size(), "the pane changed under a refused read");
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
