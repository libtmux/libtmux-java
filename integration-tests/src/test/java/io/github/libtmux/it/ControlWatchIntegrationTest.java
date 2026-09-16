package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.control.EventSubscription;
import io.github.libtmux.junit5.TmuxExtension;
import java.time.Duration;
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

        try (ControlClient client = ControlClient.attach(server.config(), session.id());
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

        try (ControlClient client = ControlClient.attach(server.config(), session.id());
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

    /**
     * A watch is the general form: any tmux format, reported when its value changes. The comparison
     * happens inside tmux, so nothing here polls.
     */
    @Test
    void aWatchedFormatIsReportedWhenItsValueChanges(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = ControlClient.attach(server.config(), session.id());
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

        try (ControlClient client = ControlClient.attach(server.config(), session.id());
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
                                                    .filter(made.id().value()::equals)
                                                    .isPresent()),
                    "the watched value did not carry its target window");
        }
    }

    @Test
    void aWatchThatIsRemovedStopsBeingReported(Server server) throws Exception {
        Session session = server.sessions().get(0);

        try (ControlClient client = ControlClient.attach(server.config(), session.id());
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
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var event = events.next(Duration.ofNanos(Math.max(0L, deadline - System.nanoTime())));
            if (event.isEmpty()) {
                return false;
            }
            if (match.test(event.orElseThrow())) {
                return true;
            }
        }
        return false;
    }
}
