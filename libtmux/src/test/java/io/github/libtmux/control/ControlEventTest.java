package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.WindowId;
import io.github.libtmux.WindowLayout;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Reading what tmux volunteers.
 *
 * <p>The shapes here are tmux's own, taken from {@code control-notify.c} and {@code control.c} and
 * documented in {@code docs/decisions/0014-watch-a-server-with-refresh-client.md}.
 */
final class ControlEventTest {

    @Test
    void aPlainNotificationIsItsNameAndTheFieldsAfterIt() {
        ControlEvent event = ControlEvent.parse("%window-add @1").orElseThrow();

        assertEquals("window-add", event.kind());
        assertEquals(List.of("@1"), event.fields());
        assertEquals(Optional.of(new WindowId("@1")), event.windowId());
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
        assertEquals(Optional.of(new WindowId("@1")), event.windowId());
        assertEquals(Optional.of("my window name"), event.value());
    }

    @Test
    void aSubscriptionOverPanesSaysWhichPane() {
        ControlEvent event = ControlEvent.parse("%subscription-changed panecmd $0 @1 2 %3 : nvim")
                .orElseThrow();

        assertEquals(Optional.of(new PaneId("%3")), event.paneId());
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

    // ---------------------------------------------------------------------------- the typed view

    @Test
    void eachFormTmuxSendsReadsAsItsOwnRecord() {
        assertEquals(new Notification.WindowAdded(new WindowId("@4"), true), typed("%window-add @4"));
        assertEquals(new Notification.WindowAdded(new WindowId("@4"), false), typed("%unlinked-window-add @4"));
        assertEquals(new Notification.WindowClosed(new WindowId("@4"), true), typed("%window-close @4"));
        assertEquals(
                new Notification.WindowPaneChanged(new WindowId("@1"), new PaneId("%7")),
                typed("%window-pane-changed @1 %7"));
        assertEquals(
                new Notification.LayoutChanged(new WindowId("@1"), new WindowLayout.Classic("b25d,80x24,0,0,1")),
                typed("%layout-change @1 b25d,80x24,0,0,1 b25d,80x24,0,0,1 *"));
        assertEquals(
                new Notification.SessionWindowChanged(new SessionId("$0"), new WindowId("@2")),
                typed("%session-window-changed $0 @2"));
        assertEquals(new Notification.SessionsChanged(), typed("%sessions-changed"));
        assertEquals(new Notification.PaneModeChanged(new PaneId("%3")), typed("%pane-mode-changed %3"));
        assertEquals(new Notification.ClientDetached("/dev/pts/4"), typed("%client-detached /dev/pts/4"));
        assertEquals(new Notification.PasteBufferChanged("buffer0"), typed("%paste-buffer-changed buffer0"));
        assertEquals(new Notification.Exit(Optional.empty()), typed("%exit"));
        assertEquals(new Notification.Exit(Optional.of("server exited")), typed("%exit server exited"));
        assertEquals(new Notification.Pause(new PaneId("%3")), typed("%pause %3"));
        assertEquals(new Notification.Continue(new PaneId("%3")), typed("%continue %3"));
        assertEquals(new Notification.Message("build  done"), typed("%message build  done"));
        assertEquals(
                new Notification.ConfigError("/etc/tmux.conf:3: unknown command: sett"),
                typed("%config-error /etc/tmux.conf:3: unknown command: sett"));
    }

    /** A name is everything after the id, spaces and all, as tmux wrote it. */
    @Test
    void aNameIsReadWholeWithItsSpaces() {
        assertEquals(
                new Notification.WindowRenamed(new WindowId("@1"), "my  build  logs", true),
                typed("%window-renamed @1 my  build  logs"));
        assertEquals(
                new Notification.SessionRenamed(new SessionId("$2"), "release candidate"),
                typed("%session-renamed $2 release candidate"));
        assertEquals(
                new Notification.ClientSessionChanged("/dev/pts/1", new SessionId("$0"), "a b"),
                typed("%client-session-changed /dev/pts/1 $0 a b"));
    }

    /** Only a subscription separates a value with {@code " : "}; a name may hold one. */
    @Test
    void aNameHoldingTheSubscriptionSeparatorIsReadWhole() {
        assertEquals(
                new Notification.WindowRenamed(new WindowId("@1"), "left : right", true),
                typed("%window-renamed @1 left : right"));
        assertEquals(
                new Notification.SessionRenamed(new SessionId("$2"), "a : b : c"),
                typed("%session-renamed $2 a : b : c"));
        assertEquals(
                Optional.empty(),
                ControlEvent.parse("%window-renamed @1 left : right")
                        .orElseThrow()
                        .value());
    }

    @Test
    void aSubscriptionCarriesItsValueAndOnlyTheTargetsTmuxNamed() {
        assertEquals(
                new Notification.SubscriptionChanged(
                        "cmd",
                        "vim README.md",
                        Optional.of(new SessionId("$0")),
                        Optional.of(new WindowId("@1")),
                        Optional.of(new PaneId("%2"))),
                typed("%subscription-changed cmd $0 @1 0 %2 : vim README.md"));
        assertEquals(
                new Notification.SubscriptionChanged(
                        "sessions", "3", Optional.of(new SessionId("$0")), Optional.empty(), Optional.empty()),
                typed("%subscription-changed sessions $0 - - - : 3"));
    }

    /**
     * tmux adds notifications between releases; one this library does not model still arrives, as
     * itself, rather than being dropped — and so does one whose id is not in a form it accepts.
     */
    @Test
    void whatIsNotModelledArrivesAsUnknownWithEverythingTmuxWrote() {
        ControlEvent newer = ControlEvent.parse("%pane-floated %4 12 3").orElseThrow();

        assertEquals(new Notification.Unknown("pane-floated"), newer.notification());
        assertEquals(List.of("%4", "12", "3"), newer.fields());
        assertEquals(new Notification.Unknown("window-add"), typed("%window-add not-a-window"));
    }

    @Test
    void anEventNamesItsPaneAndWindowWithTheirOwnTypes() {
        ControlEvent event = ControlEvent.parse("%window-pane-changed @1 %7").orElseThrow();

        assertEquals(Optional.of(new WindowId("@1")), event.windowId());
        assertEquals(Optional.of(new PaneId("%7")), event.paneId());
    }

    /** The value is everything after the first separator, including another one. */
    @Test
    void aSubscriptionValueKeepsALaterSeparator() {
        ControlEvent event = ControlEvent.parse("%subscription-changed title $0 @1 0 %2 : hello : world")
                .orElseThrow();

        assertEquals(Optional.of("hello : world"), event.value());
    }

    /** Garbage is either not an event or a readable one. It is never a thrown parse. */
    @Test
    void randomLinesEitherAreNotEventsOrNameOneWord() {
        Random random = new Random(23);
        for (int sample = 0; sample < 200; sample++) {
            String line = randomLine(random);
            ControlEvent.parse(line).ifPresent(event -> {
                assertTrue(event.kind().chars().noneMatch(Character::isWhitespace), line);
                event.fields().forEach(field -> assertTrue(!field.isBlank(), line));
            });
        }
    }

    private static String randomLine(Random random) {
        int length = random.nextInt(48);
        StringBuilder line = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            line.append(
                    switch (random.nextInt(6)) {
                        case 0 -> "%";
                        case 1 -> " ";
                        case 2 -> ":";
                        case 3 -> "@$";
                        case 4 -> " : ";
                        default -> String.valueOf((char) ('a' + random.nextInt(26)));
                    });
        }
        return line.toString();
    }

    private static Notification typed(String line) {
        return ControlEvent.parse(line).orElseThrow().notification();
    }
}
