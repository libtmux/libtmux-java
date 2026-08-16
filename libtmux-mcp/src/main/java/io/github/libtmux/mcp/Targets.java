package io.github.libtmux.mcp;

import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.WindowId;
import java.util.List;

/**
 * Finds the thing a model asked for, or says what to do about not finding it.
 *
 * <p>Every failure here names the tool that produces a working target. A model that reads "no pane
 * %9" can guess; one that reads "call tmux_list_panes for the ids that exist" cannot get stuck.
 *
 * <p>Targets are ids, never positions. A model works from a listing it read some turns ago, and
 * indexes move as neighbours come and go, so a positional target would quietly act on a pane that
 * was not the one it meant.
 */
final class Targets {

    private Targets() {}

    static Pane pane(Server server, String id) {
        PaneId wanted = paneId(id);
        List<Pane> panes = server.panes();
        return panes.stream()
                .filter(pane -> pane.id().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new ObjectDoesNotExist("no pane " + id
                        + " on this server; call tmux_list_panes for the " + panes.size() + " that exist"));
    }

    static Window window(Server server, String id) {
        WindowId wanted = windowId(id);
        List<Window> windows = server.windows();
        return windows.stream()
                .filter(window -> window.id().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new ObjectDoesNotExist("no window " + id
                        + " on this server; call tmux_list_windows for the " + windows.size() + " that exist"));
    }

    static Session session(Server server, String name) {
        List<Session> sessions = server.sessions();
        return sessions.stream()
                .filter(Session_.name().is(name))
                .findFirst()
                .orElseThrow(() -> new ObjectDoesNotExist("no session named '" + name + "'; this server has "
                        + sessions.stream().map(Session::name).toList()));
    }

    /**
     * tmux reads a bare number as an index, so {@code 1} sent where {@code %1} was meant would act
     * on a real but unintended pane. Rejected before it reaches tmux, naming the shape wanted.
     */
    static PaneId paneId(String id) {
        if (!id.startsWith("%")) {
            throw new IllegalArgumentException("'" + id + "' is not a pane id; those start with %, as in %1. "
                    + "A bare number is a pane index, which tmux would read as a different pane");
        }
        return new PaneId(id);
    }

    static WindowId windowId(String id) {
        if (!id.startsWith("@")) {
            throw new IllegalArgumentException("'" + id + "' is not a window id; those start with @, as in @1");
        }
        return new WindowId(id);
    }
}
