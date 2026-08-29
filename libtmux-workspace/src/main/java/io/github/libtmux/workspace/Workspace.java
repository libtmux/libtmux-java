package io.github.libtmux.workspace;

import java.util.List;

/**
 * A session described in a file, before anything has been built.
 *
 * @param sessionName the session to create
 * @param windows the windows to create, in order
 */
public record Workspace(String sessionName, List<WindowSpec> windows) {

    public Workspace {
        windows = List.copyOf(windows);
        if (sessionName.isEmpty()) {
            throw new IllegalArgumentException("the workspace has no session name");
        }
        if (sessionName.indexOf('.') >= 0 || sessionName.indexOf(':') >= 0) {
            throw new IllegalArgumentException("workspace session names cannot contain '.' or ':': " + sessionName);
        }
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("the workspace has no windows");
        }
    }
}
