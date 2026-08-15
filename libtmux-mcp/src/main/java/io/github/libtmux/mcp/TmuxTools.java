package io.github.libtmux.mcp;

import io.github.libtmux.ObjectDoesNotExist;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.query.Selections;
import java.util.Collection;
import java.util.List;

/**
 * What a model can do to a tmux server.
 *
 * <p>Separate from the protocol wiring, because what a tool does to tmux is worth testing against
 * real tmux and attaching it to a transport is not.
 *
 * <p>Every tool addresses a pane by its id rather than by position. A model works from a listing it
 * read some time ago, and pane indexes move as neighbours come and go, so a positional target would
 * quietly act on the wrong pane.
 */
public final class TmuxTools {

    private final Server server;

    public TmuxTools(Server server) {
        this.server = server;
    }

    /** Every session, with the names of its windows. */
    public List<SessionSummary> sessions() {
        return server.sessions().stream()
                .map(session -> new SessionSummary(
                        session.name(),
                        session.id().value(),
                        session.attached(),
                        session.windows().stream().map(Window::name).toList()))
                .toList();
    }

    /** Every pane on the server, with the ids other tools take as targets. */
    public List<PaneSummary> panes() {
        return describe(server.panes());
    }

    /**
     * Describes panes the caller has already chosen.
     *
     * <p>Takes the panes rather than an expression to select them, so narrowing stays an ordinary
     * stream filter at the call site:
     *
     * <pre>{@code
     * tools.describe(server.panes().stream().filter(Pane_.command().startsWith("nvim")).toList());
     * }</pre>
     *
     * <p>A method taking the expression instead would read as though tmux did the selecting. It does
     * not: a capture is already in hand by then, and filtering it issues no further command.
     */
    public List<PaneSummary> describe(Collection<Pane> panes) {
        return panes.stream()
                .map(pane -> new PaneSummary(
                        pane.id().value(),
                        pane.window().name(),
                        pane.window().session().name(),
                        pane.currentCommand(),
                        pane.active()))
                .toList();
    }

    /** What a pane is currently showing, one element per line. */
    public List<String> capture(String paneId) {
        return pane(paneId).capture();
    }

    /** Runs a command in a pane, as though it had been typed there. */
    public void run(String paneId, String command) {
        pane(paneId).sendLine(command);
    }

    /**
     * Creates a window in a session and returns the id of its first pane.
     *
     * @throws ObjectDoesNotExist if no session has that name
     */
    public String newWindow(String sessionName, String windowName) {
        Session session = Selections.oneOrEmpty(server.sessions().stream()
                        .filter(Session_.name().is(sessionName))
                        .toList())
                .orElseThrow(() -> new ObjectDoesNotExist("no session named '" + sessionName + "'"));
        return session.newWindow(windowName).panes().get(0).id().value();
    }

    private Pane pane(String paneId) {
        PaneId id = new PaneId(paneId);
        return server.panes().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ObjectDoesNotExist("no pane " + paneId));
    }
}
