package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.workspace.Workspace;
import io.github.libtmux.workspace.WorkspaceBuilder;
import java.util.List;

/**
 * Builds a whole session from one description.
 *
 * <p>A three-window, six-pane workspace built call by call is a dozen tool calls, each of which can
 * half-succeed and leave the model reasoning about what it has. Built from a document it is one
 * call, and the description is checked while it is still text — a layout tmux would refuse is
 * refused before any session exists to leave half-made.
 *
 * <p>The document is the shape tmuxp uses, so a file somebody already has is one a model can send.
 */
final class Workspaces {

    private Workspaces() {}

    record BuiltPane(String id, String window) {}

    record Built(String session, String sessionId, int windows, int panes, List<BuiltPane> paneIds, String note) {}

    static Built apply(Call call) {
        String document = call.string("workspace");
        Workspace workspace = WorkspaceBuilder.parse(document);
        if (call.server().hasSession(workspace.sessionName())) {
            throw new IllegalArgumentException("a session named '" + workspace.sessionName()
                    + "' is already there; rename it in the document, or kill the one that exists first");
        }
        Session session = WorkspaceBuilder.build(call.server(), workspace);
        List<Window> windows = session.windows();
        List<BuiltPane> panes = windows.stream()
                .flatMap(window -> window.panes().stream()
                        .map(pane -> new BuiltPane(pane.id().value(), window.name())))
                .toList();
        return new Built(
                session.name(),
                session.id().value(),
                windows.size(),
                panes.size(),
                panes,
                "Built detached, so nothing a person is looking at changed. Every pane's commands were sent, "
                        + "not waited for; call tmux_wait_for_text on one to see whether it came up.");
    }

    /** What a caller is shown when it asks how to write one. */
    static String example() {
        return """
                session_name: api-work
                windows:
                  - window_name: editor
                    panes:
                      - nvim
                  - window_name: services
                    layout: even-horizontal
                    panes:
                      - npm run dev
                      - docker compose logs -f
                """;
    }

    /** Panes in the order the document described them, which is the order their ids come back in. */
    static List<String> paneIds(List<Pane> panes) {
        return panes.stream().map(pane -> pane.id().value()).toList();
    }
}
