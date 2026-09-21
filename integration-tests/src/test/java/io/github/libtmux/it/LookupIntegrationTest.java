package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.junit5.TmuxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A named session and a pane id come back as that object. */
@ExtendWith(TmuxExtension.class)
class LookupIntegrationTest {

    @Test
    void aNamedSessionIsThatSessionOnly(Server server) {
        Session created = server.newSession("lookup-target");
        server.newSession("lookup-other");

        Session found = server.session("lookup-target").orElseThrow();

        assertEquals(created.id(), found.id());
        assertEquals(1, found.windows().size());
        assertEquals("lookup-target", found.name());
    }

    @Test
    void aPaneLookupFindsThatPane(Server server) {
        Session session = server.newSession("lookup-pane");
        var pane = session.windows().get(0).panes().get(0);

        assertEquals(pane.id(), server.pane(pane.id()).orElseThrow().id());
        assertTrue(server.pane(new PaneId("%999999")).isEmpty());
    }
}
