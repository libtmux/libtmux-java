package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.Window;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.Delivery;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.control.Notification;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Being told what changed rather than asking.
 *
 * <p>tmux re-expands a watched format about once a second and writes an event only when the value
 * differs, so a client that subscribes does nothing at all between changes. That is what makes
 * watching a server cost nothing while it is idle, and it is measured here rather than assumed.
 */
@ExtendWith(TmuxExtension.class)
final class ControlWatchIntegrationTest {

    @Test
    void aWindowAppearingIsAnnouncedWithoutAnythingAsking(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {

            session.newWindow("appeared");

            assertTrue(
                    awaitEvent(events, event -> event.kind().equals("window-add")),
                    "tmux did not tell the attached control client about the new window");
        }
    }

    @Test
    void aRenameIsAnnouncedWithTheNameItWasGiven(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {

            var unused = session.windows().get(0).rename("renamed-now");

            assertTrue(
                    awaitEvent(
                            events,
                            event -> event.kind().equals("window-renamed")
                                    && event.fields().contains("renamed-now")),
                    "tmux did not report the renamed window");
        }
    }

    /** tmux writes a name as it was given; only a subscription uses {@code " : "} as a separator. */
    @Test
    void aNameHoldingTheSubscriptionSeparatorIsAnnouncedWhole(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {

            var unused = session.windows().get(0).rename("left : right");

            assertTrue(
                    awaitEvent(
                            events,
                            event -> event.notification() instanceof Notification.WindowRenamed renamed
                                    && renamed.name().equals("left : right")),
                    "tmux reported the rename, but not with the whole name");
        }
    }

    /** A message another client aims at this one arrives as a notification, " : " and all. */
    @Test
    void aMessageAimedAtTheClientArrivesTyped(Server server) throws Exception {
        if (!server.version().atLeast(new TmuxVersion(3, 4, ""))) {
            return; // %message arrived in tmux 3.4
        }
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
            String name = client.send("display-message", "-p", "#{client_name}")
                    .lines()
                    .get(0);

            var unused = server.run(java.util.List.of("display-message", "-c", name, "hello : there"));

            assertTrue(
                    awaitEvent(events, event -> event.notification().equals(new Notification.Message("hello : there"))),
                    "the message did not arrive typed and whole");
        }
    }

    /**
     * A watch is the general form: any tmux format, reported when its value changes. The comparison
     * happens inside tmux, so nothing here polls.
     */
    @Test
    void aWatchedFormatIsReportedWhenItsValueChanges(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
            client.watch("windows", "", "#{session_windows}");

            assertTrue(
                    awaitEvent(events, event -> hasSubscriptionValue(event, "windows", "1")),
                    "the first value is reported once");
            session.newWindow("another");

            assertTrue(
                    awaitEvent(events, event -> hasSubscriptionValue(event, "windows", "2")),
                    "the change is reported without being asked for");
        }
    }

    /** A watch over every window says which window each value belongs to. */
    @Test
    void aWatchOverEveryWindowNamesTheWindowEachValueIsFor(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
            client.watch("names", "@*", "#{window_name}");
            var made = session.newWindow("distinctly-named");

            assertTrue(
                    awaitEvent(
                            events,
                            event ->
                                    event.subscription().filter("names"::equals).isPresent()
                                            && event.value()
                                                    .filter("distinctly-named"::equals)
                                                    .isPresent()
                                            && event.windowId()
                                                    .filter(made.id()::equals)
                                                    .isPresent()),
                    "the watched value did not carry its target window");
        }
    }

    /**
     * {@code ControlClient.attach} now sends {@code refresh-client -f new-layouts} on connect,
     * so a {@code layout-change} notification carries JSON on tmux 3.8+ - agreeing with what a
     * plain client already reads through {@link Window#layout()} - instead of the classic string a
     * control client got before this flag was requested.
     */
    @Test
    void aLayoutChangeCarriesJsonOnceThisClientAskedForIt(Server server) throws Exception {
        Session session = server.sessions().get(0);
        Window window = session.windows().get(0);
        if (!server.version().atLeast(new TmuxVersion(3, 8, ""))) {
            return; // classic-only releases have no JSON layout to disagree about
        }

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
            window.split();

            Optional<ControlEvent> layoutChange =
                    awaitMatchingEvent(events, event -> event.kind().equals("layout-change"), Duration.ofSeconds(10));
            String jsonField =
                    layoutChange
                            .orElseThrow(() -> new AssertionError("no layout-change notification arrived"))
                            .fields()
                            .stream()
                            .filter(field -> field.startsWith("{"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no JSON-shaped field in "
                                    + layoutChange.orElseThrow().fields()));

            assertEquals(
                    window.refresh().layout().value(),
                    jsonField,
                    "the control client's own layout-change disagreed with a plain client's #{window_layout}");
        }
    }

    /**
     * The typed reading of a real notification, from a real tmux: a window renamed to a name with a
     * run of spaces in it arrives as that name, and as the window it happened to.
     */
    @Test
    void aRenameArrivesTypedWithItsNameWhole(Server server) throws Exception {
        Session session = server.sessions().get(0);
        Window window = session.windows().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
            var unused = window.rename("build  logs");

            Optional<ControlEvent> renamed = awaitMatchingEvent(
                    events,
                    event -> event.notification() instanceof Notification.WindowRenamed,
                    Duration.ofSeconds(10));

            assertEquals(
                    new Notification.WindowRenamed(window.id(), "build  logs", true),
                    renamed.orElseThrow(() -> new AssertionError("no window-renamed notification arrived"))
                            .notification());
        }
    }

    @Test
    void aWatchThatIsRemovedStopsBeingReported(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = server.control(session);
                EventSubscription<ControlEvent> events = client.subscribeEvents(32)) {
            client.watch("windows", "", "#{session_windows}");
            assertTrue(awaitEvent(
                    events,
                    event -> event.subscription().filter("windows"::equals).isPresent()));

            client.unwatch("windows");
            while (events.next(Duration.ZERO).isPresent()) {}
            session.newWindow("after-unwatching");

            assertFalse(
                    awaitEvent(
                            events,
                            event -> event.subscription()
                                    .filter("windows"::equals)
                                    .isPresent(),
                            Duration.ofMillis(2500)),
                    "nothing is reported for a watch that was removed");
        }
    }

    private static boolean hasSubscriptionValue(ControlEvent event, String name, String value) {
        return event.subscription().filter(name::equals).isPresent()
                && event.value().filter(value::equals).isPresent();
    }

    private static boolean awaitEvent(EventSubscription<ControlEvent> events, Predicate<ControlEvent> match)
            throws InterruptedException {
        return awaitEvent(events, match, Duration.ofSeconds(10));
    }

    private static boolean awaitEvent(
            EventSubscription<ControlEvent> events, Predicate<ControlEvent> match, Duration timeout)
            throws InterruptedException {
        return awaitMatchingEvent(events, match, timeout).isPresent();
    }

    /** As {@link #awaitEvent}, keeping the event that matched rather than only whether one did. */
    private static Optional<ControlEvent> awaitMatchingEvent(
            EventSubscription<ControlEvent> events, Predicate<ControlEvent> match, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var step = events.next(Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())));
            if (step.isEmpty() || !(step.orElseThrow() instanceof Delivery.Event<ControlEvent> event)) {
                if (step.isEmpty()) {
                    return Optional.empty();
                }
                continue;
            }
            if (match.test(event.value())) {
                return Optional.of(event.value());
            }
        }
        return Optional.empty();
    }
}
