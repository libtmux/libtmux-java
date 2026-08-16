package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.control.ControlClient;
import io.github.libtmux.control.ControlEvent;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
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
        List<ControlEvent> seen = new CopyOnWriteArrayList<>();

        try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
            client.onEvent(seen::add);

            session.newWindow("appeared");

            assertTrue(
                    await(() -> seen.stream().anyMatch(event -> event.kind().equals("window-add"))),
                    "tmux tells an attached control client about a new window: " + kinds(seen));
        }
    }

    @Test
    void aRenameIsAnnouncedWithTheNameItWasGiven(Server server) throws Exception {
        Session session = server.sessions().get(0);
        List<ControlEvent> seen = new CopyOnWriteArrayList<>();

        try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
            client.onEvent(seen::add);

            session.windows().get(0).rename("renamed-now");

            assertTrue(
                    await(() -> seen.stream()
                            .anyMatch(event -> event.kind().equals("window-renamed")
                                    && event.fields().contains("renamed-now"))),
                    String.valueOf(kinds(seen)));
        }
    }

    /**
     * A watch is the general form: any tmux format, reported when its value changes. The comparison
     * happens inside tmux, so nothing here polls.
     */
    @Test
    void aWatchedFormatIsReportedWhenItsValueChanges(Server server) throws Exception {
        Session session = server.sessions().get(0);
        List<ControlEvent> seen = new CopyOnWriteArrayList<>();

        try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
            client.onEvent(seen::add);
            client.watch("windows", "", "#{session_windows}");

            assertTrue(await(() -> valuesOf(seen, "windows").contains("1")), "the first value is reported once");
            session.newWindow("another");

            assertTrue(
                    await(() -> valuesOf(seen, "windows").contains("2")),
                    "and the change is reported without being asked for: " + valuesOf(seen, "windows"));
        }
    }

    /** A watch over every window says which window each value belongs to. */
    @Test
    void aWatchOverEveryWindowNamesTheWindowEachValueIsFor(Server server) throws Exception {
        Session session = server.sessions().get(0);
        List<ControlEvent> seen = new CopyOnWriteArrayList<>();

        try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
            client.onEvent(seen::add);
            client.watch("names", "@*", "#{window_name}");
            var made = session.newWindow("distinctly-named");

            assertTrue(
                    await(() -> seen.stream()
                            .anyMatch(event ->
                                    event.subscription().filter("names"::equals).isPresent()
                                            && event.value()
                                                    .filter("distinctly-named"::equals)
                                                    .isPresent()
                                            && event.windowId()
                                                    .filter(made.id().value()::equals)
                                                    .isPresent())),
                    "each value carries its own target: " + seen);
        }
    }

    @Test
    void aWatchThatIsRemovedStopsBeingReported(Server server) throws Exception {
        Session session = server.sessions().get(0);
        List<ControlEvent> seen = new CopyOnWriteArrayList<>();

        try (ControlClient client = ControlClient.attach(server.config(), session.id())) {
            client.onEvent(seen::add);
            client.watch("windows", "", "#{session_windows}");
            assertTrue(await(() -> !valuesOf(seen, "windows").isEmpty()));

            client.unwatch("windows");
            seen.clear();
            session.newWindow("after-unwatching");
            Thread.sleep(2500);

            assertEquals(List.of(), valuesOf(seen, "windows"), "nothing is reported for a watch that was removed");
        }
    }

    private static List<String> valuesOf(List<ControlEvent> events, String name) {
        return events.stream()
                .filter(event -> event.subscription().filter(name::equals).isPresent())
                .flatMap(event -> event.value().stream())
                .toList();
    }

    private static List<String> kinds(List<ControlEvent> events) {
        return events.stream().map(ControlEvent::kind).distinct().toList();
    }

    /** tmux checks a subscription about once a second, so waiting has to outlast that. */
    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
