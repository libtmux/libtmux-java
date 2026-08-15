package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Dimensions;
import com.git_pull.libtmux.Direction;
import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Server;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.WindowId;
import com.git_pull.libtmux.control.ControlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Moving around a session, and resizing a pane by direction rather than by flag. */
@ExtendWith(TmuxExtension.class)
final class NavigationIntegrationTest {

    private static WindowId active(Session session) {
        return session.refresh().activeWindow().orElseThrow().id();
    }

    @Test
    void selectingAWindowMakesItActive(Server server) {
        Session session = server.sessions().get(0);
        Window first = session.windows().get(0);
        session.newWindow("second");

        session.selectWindow(first);

        assertEquals(first.id(), active(session));
    }

    @Test
    void nextAndPreviousMoveThroughTheWindows(Server server) {
        Session session = server.sessions().get(0);
        Window first = session.windows().get(0);
        Window second = session.newWindow("second");
        session.selectWindow(first);

        session.nextWindow();
        assertEquals(second.id(), active(session));

        session.previousWindow();
        assertEquals(first.id(), active(session));
    }

    @Test
    void nextWrapsAtTheEnd(Server server) {
        Session session = server.sessions().get(0);
        Window first = session.windows().get(0);
        Window second = session.newWindow("second");
        session.selectWindow(second);

        session.nextWindow();

        assertEquals(first.id(), active(session), "the last window's next is the first");
    }

    @Test
    void lastReturnsToWhereYouWere(Server server) {
        Session session = server.sessions().get(0);
        Window first = session.windows().get(0);
        Window second = session.newWindow("second");
        session.selectWindow(first);
        session.selectWindow(second);

        session.lastWindow();

        assertEquals(first.id(), active(session));
    }

    /** tmux reports having nowhere to go back to, rather than silently staying put. */
    @Test
    void lastWithNowhereToGoBackToIsReported(Server server) {
        Session session = server.sessions().get(0);

        assertThrows(LibTmuxException.class, session::lastWindow);
    }

    @Test
    void detachingLeavesTheSessionRunning(Server server) throws Exception {
        Session session = server.sessions().get(0);
        try (ControlClient attached = ControlClient.attach(server.config(), session.id())) {
            assertTrue(attached.send("display-message", "-p", "ready").succeeded());
            assertTrue(await(() -> !server.clients().isEmpty()));

            session.detachClients();

            assertTrue(await(() -> server.clients().isEmpty()), "the client went");
            assertTrue(server.hasSession(session.name()), "and the session stayed");
        }
    }

    // ----------------------------------------------------------------------------- pane resize

    @Test
    void resizingByDirectionChangesTheSize(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();
        Pane top = window.refresh().panes().get(0);
        Dimensions before = top.size();

        top.resize(Direction.DOWN, 3);

        Dimensions after = top.refresh().size();
        assertEquals(before.width(), after.width(), "growing downwards does not change the width");
        assertEquals(before.height() + 3, after.height());
    }

    @Test
    void anAbsoluteResizeSetsBothAtOnce(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.split();
        Pane top = window.refresh().panes().get(0);

        top.resizeTo(new Dimensions(40, 10));

        assertEquals(10, top.refresh().size().height());
    }

    @Test
    void aResizeOfNothingIsRejected(Server server) {
        Pane pane = server.panes().get(0);

        assertThrows(IllegalArgumentException.class, () -> pane.resize(Direction.UP, 0));
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
