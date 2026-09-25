package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.libtmux.Client;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.snapshot.ServerMirror;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A mirror follows what tmux announces, and says so when it can no longer follow. */
@ExtendWith(TmuxExtension.class)
final class ServerMirrorIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Test
    void aNewWindowIsPublished(Server server) throws Exception {
        Session anchor = server.sessions().get(0);
        try (ServerMirror mirror = ServerMirror.open(anchor)) {
            long before = mirror.current().epoch();

            anchor.newWindow("announced");

            ServerMirror.View view = awaitUntil(mirror, seen -> hasWindow(seen, "announced"));
            assertTrue(view.epoch() > before, "a changed server is a newer view");
        }
    }

    @Test
    void aNewerViewWakesAnArmedCallback(Server server) throws Exception {
        Session anchor = server.sessions().get(0);
        try (ServerMirror mirror = ServerMirror.open(anchor)) {
            CountDownLatch woken = new CountDownLatch(1);
            mirror.onNewer(mirror.current().epoch(), woken::countDown);

            anchor.newWindow("wakes");

            assertTrue(woken.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS), "the callback never ran");
        }
    }

    /** tmux replays nothing, so a lost control client means a fresh capture through the same session. */
    @Test
    void aDetachedListenerReattachesThroughTheSameSession(Server server) throws Exception {
        Session anchor = server.sessions().get(0);
        Set<String> before = server.clients().stream().map(Client::name).collect(Collectors.toSet());
        try (ServerMirror mirror = ServerMirror.open(anchor)) {
            Client listener = server.clients().stream()
                    .filter(client -> !before.contains(client.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the mirror attached no client"));

            listener.detach();
            assertTrue(
                    Await.until(() ->
                            server.clients().stream().noneMatch(c -> c.name().equals(listener.name()))),
                    "the listener never detached");
            anchor.newWindow("after-reattach");

            awaitUntil(mirror, seen -> hasWindow(seen, "after-reattach"));
            assertFalse(mirror.isEnded(), "a mirror whose session remains carries on");
        }
    }

    @Test
    void theAnchorSessionEndingEndsTheMirror(Server server) throws Exception {
        server.newSession("survivor");
        Session anchor = server.newSession("anchor");
        try (ServerMirror mirror = ServerMirror.open(anchor)) {
            anchor.kill();

            assertTrue(Await.until(mirror::isEnded), "the mirror outlived its session");
            assertInstanceOf(TargetGoneException.class, mirror.cause().orElseThrow());
        }
    }

    /** An announcement that changed nothing costs a rebuild, and wakes no one. */
    @Test
    void aRebuildThatFindsNothingNewPublishesNothing(Server fixture) throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        ServerConfig counted = fixture.config().toBuilder()
                .observer(report -> dispatches.incrementAndGet())
                .build();
        try (Server server = Server.open(counted);
                ServerMirror mirror = ServerMirror.open(server.sessions().get(0))) {
            Window window = server.sessions().get(0).windows().get(0);
            long epoch = mirror.current().epoch();
            int before = dispatches.get();

            server.cmd("rename-window", "-t", window.id().value(), window.name());

            // The rename is one command; a rebuild is two more.
            assertTrue(
                    Await.until(() -> dispatches.get() >= before + 3),
                    "tmux announced nothing, so this proves nothing");
            assertEquals(Optional.empty(), mirror.awaitNewer(epoch, Duration.ofMillis(300)));
        }
    }

    @Test
    void closingEndsWithoutACause(Server server) throws Exception {
        ServerMirror mirror = ServerMirror.open(server.sessions().get(0));
        long epoch = mirror.current().epoch();

        mirror.close();

        assertTrue(mirror.isEnded());
        assertEquals(Optional.empty(), mirror.cause());
        assertEquals(Optional.empty(), mirror.awaitNewer(epoch, PATIENCE), "a closed mirror does not wait");
    }

    /**
     * tmux announces no pane title change, so a mirror that only listens never sees one, and one
     * that also rebuilds periodically does.
     */
    @Test
    void aPeriodicRebuildSeesWhatTmuxDoesNotAnnounce(Server fixture) throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        ServerConfig counted = fixture.config().toBuilder()
                .observer(report -> dispatches.incrementAndGet())
                .build();
        // A named window running no shell: no prompt retitles it and automatic-rename is off, so
        // nothing tmux announces follows the retitle.
        Session elsewhere = fixture.newSession(
                s -> s.named("elsewhere").firstWindowNamed("quiet").running("sleep", "600"));
        String pane = elsewhere.windows().get(0).panes().get(0).id().value();
        try (Server server = Server.open(counted);
                ServerMirror listening = ServerMirror.open(server.sessions().get(0));
                ServerMirror periodic = ServerMirror.open(fixture.sessions().get(0), Duration.ofMillis(200))) {
            awaitQuiet(dispatches);
            long epoch = listening.current().epoch();

            fixture.cmd("select-pane", "-t", pane, "-T", "retitled-quietly");

            awaitUntil(
                    periodic,
                    seen -> seen.snapshot().panes().stream()
                            .anyMatch(state -> state.title().equals("retitled-quietly")));
            assertEquals(Optional.empty(), listening.awaitNewer(epoch, Duration.ofMillis(500)), "tmux announced it");
        }
    }

    /** Waits until the mirror's own attach has stopped causing rebuilds. */
    private static void awaitQuiet(AtomicInteger dispatches) throws InterruptedException {
        int seen = -1;
        while (seen != dispatches.get()) {
            seen = dispatches.get();
            Thread.sleep(300);
        }
    }

    private static boolean hasWindow(ServerMirror.View view, String name) {
        return view.snapshot().windows().stream()
                .anyMatch(window -> window.name().equals(name));
    }

    private static ServerMirror.View awaitUntil(ServerMirror mirror, Predicate<ServerMirror.View> wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        ServerMirror.View view = mirror.current();
        while (!wanted.test(view)) {
            if (mirror.isEnded()) {
                fail("the mirror ended: " + mirror.cause());
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                fail("the mirror never showed it; the last view was epoch " + view.epoch());
            }
            view = mirror.awaitNewer(view.epoch(), Duration.ofNanos(left)).orElse(view);
        }
        return view;
    }
}
