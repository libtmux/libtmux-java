package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reading what tmux volunteers.
 *
 * <p>The shapes here are tmux's own, taken from {@code control-notify.c} and {@code control.c} and
 * confirmed against a running 3.7 server in {@code docs/spikes/23-control-subscriptions.md}.
 */
final class ControlEventTest {

    @Test
    void aPlainNotificationIsItsNameAndTheFieldsAfterIt() {
        ControlEvent event = ControlEvent.parse("%window-add @1").orElseThrow();

        assertEquals("window-add", event.kind());
        assertEquals(List.of("@1"), event.fields());
        assertEquals(Optional.of("@1"), event.windowId());
        assertEquals(Optional.empty(), event.value());
    }

    @Test
    void aRenameKeepsTheNameItWasGiven() {
        ControlEvent event =
                ControlEvent.parse("%window-renamed @1 renamed-now").orElseThrow();

        assertEquals("window-renamed", event.kind());
        assertEquals(List.of("@1", "renamed-now"), event.fields());
    }

    /**
     * A subscription carries the expanded format after a {@code :}, and a format may expand to text
     * with spaces in it, so the value is taken whole rather than split.
     */
    @Test
    void aSubscriptionCarriesItsNameItsTargetAndWhateverTheFormatExpandedTo() {
        ControlEvent event = ControlEvent.parse("%subscription-changed winnames $0 @1 2 - : my window name")
                .orElseThrow();

        assertEquals("subscription-changed", event.kind());
        assertEquals(Optional.of("winnames"), event.subscription());
        assertEquals(Optional.of("$0"), Optional.of(event.fields().get(1)));
        assertEquals(Optional.of("@1"), event.windowId());
        assertEquals(Optional.of("my window name"), event.value());
    }

    @Test
    void aSubscriptionOverPanesSaysWhichPane() {
        ControlEvent event = ControlEvent.parse("%subscription-changed panecmd $0 @1 2 %3 : nvim")
                .orElseThrow();

        assertEquals(Optional.of("%3"), event.paneId());
        assertEquals(Optional.of("nvim"), event.value());
    }

    /** An empty expansion is a value, not the absence of one: it is how a format says "nothing". */
    @Test
    void aSubscriptionThatExpandedToNothingStillCarriesAValue() {
        ControlEvent event =
                ControlEvent.parse("%subscription-changed empty $0 - - - : ").orElseThrow();

        assertEquals(Optional.of(""), event.value());
    }

    @Test
    void aLineThatIsNotANotificationIsNotOne() {
        assertTrue(ControlEvent.parse("ordinary output").isEmpty());
        assertTrue(ControlEvent.parse("%").isEmpty());
        assertTrue(ControlEvent.parse("").isEmpty());
    }
}
