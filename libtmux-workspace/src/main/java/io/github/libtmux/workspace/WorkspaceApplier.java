package io.github.libtmux.workspace;

import io.github.libtmux.Layout;
import io.github.libtmux.Layouts;
import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Applies a validated workspace as one session and attempts to remove it after a failure. */
final class WorkspaceApplier {

    private WorkspaceApplier() {}

    static Session build(Server server, Workspace workspace) {
        validate(server, workspace);
        String staging = "libtmux-ws-" + UUID.randomUUID();
        Session session = null;
        try {
            session = server.newSession(staging);
            session = session.rename(workspace.sessionName());
            List<BuiltWindow> windows = createTopology(session, workspace.windows());
            runCommands(windows);
            return session.refresh();
        } catch (RuntimeException | Error failure) {
            if (session == null) {
                cleanupStaging(server, staging, failure);
            } else {
                try {
                    session.kill();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private static void validate(Server server, Workspace workspace) {
        for (WindowSpec window : workspace.windows()) {
            window.layout().ifPresent(value -> {
                Layouts.require(value);
                builtIn(value).ifPresent(layout -> layout.requireSupported(server.version()));
            });
        }
    }

    private static void cleanupStaging(Server server, String staging, Throwable failure) {
        try {
            CommandResult cleanup = server.cmd("kill-session", "-t", "=" + staging);
            if (!cleanup.succeeded() && cleanup.stderr().stream().noneMatch(WorkspaceApplier::alreadyAbsent)) {
                failure.addSuppressed(new LibTmuxException(
                        "could not clean up staging session: " + String.join("; ", cleanup.stderr())));
            }
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static boolean alreadyAbsent(String message) {
        return message.contains("can't find session")
                || message.contains("no server running")
                || message.contains("server exited unexpectedly");
    }

    private static List<BuiltWindow> createTopology(Session session, List<WindowSpec> specs) {
        List<BuiltWindow> windows = new ArrayList<>(specs.size());
        for (int index = 0; index < specs.size(); index++) {
            WindowSpec spec = specs.get(index);
            Window window = index == 0 ? firstWindow(session, spec.name()) : session.newWindow(spec.name());
            for (int pane = 1; pane < spec.panes().size(); pane++) {
                window.split();
            }
            applyLayout(window, spec.layout());
            List<Pane> panes = window.refresh().panes();
            requirePaneCount(panes, spec);
            windows.add(new BuiltWindow(spec, panes));
        }
        return List.copyOf(windows);
    }

    private static Window firstWindow(Session session, String name) {
        Window window = session.windows().getFirst();
        return name.isEmpty() ? window : window.rename(name);
    }

    private static void applyLayout(Window window, Optional<String> layout) {
        layout.ifPresent(
                value -> builtIn(value).ifPresentOrElse(window::selectLayout, () -> window.applyLayout(value)));
    }

    private static Optional<Layout> builtIn(String layout) {
        for (Layout candidate : Layout.values()) {
            if (candidate.tmuxName().equals(layout)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void runCommands(List<BuiltWindow> windows) {
        for (BuiltWindow window : windows) {
            WindowSpec spec = window.specification();
            List<Pane> panes = window.panes();
            for (int paneIndex = 0; paneIndex < panes.size(); paneIndex++) {
                for (String command : spec.panes().get(paneIndex).commands()) {
                    panes.get(paneIndex).sendLine(command);
                }
            }
        }
    }

    private static void requirePaneCount(List<Pane> panes, WindowSpec spec) {
        if (panes.size() != spec.panes().size()) {
            throw new IllegalStateException("window '" + spec.name() + "' has " + panes.size() + " panes; expected "
                    + spec.panes().size());
        }
    }

    private record BuiltWindow(WindowSpec specification, List<Pane> panes) {}
}
