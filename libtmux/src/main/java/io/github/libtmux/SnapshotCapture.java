package io.github.libtmux;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.snapshot.ClientState;
import io.github.libtmux.snapshot.PaneState;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.SessionState;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.snapshot.WindowState;
import io.github.libtmux.transport.CommandResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads a whole tmux server into one snapshot.
 *
 * <p>One server-wide listing per kind of object, so ordering and membership stay tmux's decision
 * rather than being re-derived from another listing's rows.
 *
 * <p>The server's process identity is read before and after, and a capture that spanned a
 * replacement is discarded rather than returned: rows from two servers form a graph that never
 * existed. Retrying is {@link Server#snapshot()}'s decision, not this one's.
 */
final class SnapshotCapture {

    private static final RowFormat SESSIONS =
            RowFormat.of("session_id", "session_name", "session_attached", "session_windows");
    private static final RowFormat WINDOWS = RowFormat.of(
            "session_id",
            "window_id",
            "window_index",
            "window_name",
            "window_active",
            "window_panes",
            "window_linked",
            "window_width",
            "window_height",
            "window_layout");
    private static final String[] PANE_FIELDS = {
        "session_id",
        "window_id",
        "window_index",
        "pane_id",
        "pane_index",
        "pane_active",
        "pane_current_command",
        "pane_width",
        "pane_height",
        "pane_title",
        "pane_current_path",
        "pane_pid",
        "pane_at_top",
        "pane_at_bottom",
        "pane_at_left",
        "pane_at_right"
    };

    private static final RowFormat PANES = RowFormat.of(PANE_FIELDS);

    /** tmux gained pane_floating_flag in 3.7; before that the format expands to nothing. */
    private static final TmuxVersion FLOATING_SINCE = new TmuxVersion(3, 7, "");

    private static final String FLOATING = "pane_floating_flag";

    private static final RowFormat PANES_WITH_FLOATING = RowFormat.of(withFloating());

    private static final RowFormat CLIENTS = RowFormat.of("client_name", "session_id");
    private static final RowFormat PROCESS = RowFormat.of("pid", "version");

    private final Server server;

    SnapshotCapture(Server server) {
        this.server = server;
    }

    /** One attempt, empty when the server was replaced under it. */
    Optional<ServerSnapshot> attempt() {
        Optional<ServerProcess> observed = process();
        if (observed.isEmpty()) {
            return Optional.of(ServerSnapshot.of(Instant.now(), List.of(), List.of(), List.of(), List.of()));
        }
        ServerProcess process = observed.orElseThrow();
        ServerSnapshot captured;
        try {
            captured = capture(process);
        } catch (RuntimeException failure) {
            Optional<ServerProcess> current;
            try {
                current = process();
            } catch (RuntimeException probeFailure) {
                probeFailure.addSuppressed(failure);
                throw probeFailure;
            }
            if (Optional.of(process).equals(current)) {
                throw failure;
            }
            return Optional.empty();
        }
        if (!Optional.of(process).equals(process())) {
            return Optional.empty();
        }
        return Optional.of(captured);
    }

    /** Reads process identity and version together so neither can come from a different server. */
    Optional<ServerProcess> process() {
        CommandResult result = server.cmd("display-message", "-p", PROCESS.template());
        if (!result.succeeded()) {
            if (result.stderr().stream().anyMatch(SnapshotCapture::serverAbsent)) {
                return Optional.empty();
            }
            throw new LibTmuxException("tmux display-message failed: " + String.join("; ", result.stderr()));
        }
        List<RowFormat.Row> reported = PROCESS.rows(result.stdout());
        if (reported.size() != 1) {
            throw new LibTmuxException("tmux did not report exactly one server identity row");
        }
        RowFormat.Row row = reported.get(0);
        long pid = row.count("pid");
        if (pid <= 0) {
            throw new LibTmuxException("tmux reported a malformed server pid: " + pid);
        }
        return Optional.of(new ServerProcess(pid, TmuxVersion.parse(row.text("version"))));
    }

    private ServerSnapshot capture(ServerProcess process) {
        List<SessionState> sessions = new ArrayList<>();
        for (RowFormat.Row row : rows(SESSIONS, "list-sessions")) {
            sessions.add(new SessionState(
                    new SessionId(row.text("session_id")),
                    row.text("session_name"),
                    row.count("session_attached") > 0,
                    row.number("session_windows")));
        }
        if (sessions.isEmpty()) {
            return ServerSnapshot.of(
                    Instant.now(), process.pid(), process.version(), sessions, List.of(), List.of(), List.of());
        }
        List<WindowState> windows = new ArrayList<>();
        for (RowFormat.Row row : rows(WINDOWS, "list-windows", "-a")) {
            windows.add(new WindowState(
                    context(row),
                    row.text("window_name"),
                    row.flag("window_active"),
                    row.number("window_panes"),
                    row.flag("window_linked"),
                    new Dimensions(row.number("window_width"), row.number("window_height")),
                    row.text("window_layout")));
        }
        boolean floatingKnown = process.version().atLeast(FLOATING_SINCE);
        List<PaneState> panes = new ArrayList<>();
        for (RowFormat.Row row : rows(floatingKnown ? PANES_WITH_FLOATING : PANES, "list-panes", "-a")) {
            panes.add(new PaneState(
                    context(row),
                    new PaneId(row.text("pane_id")),
                    row.number("pane_index"),
                    row.flag("pane_active"),
                    row.text("pane_current_command"),
                    new Dimensions(row.number("pane_width"), row.number("pane_height")),
                    row.text("pane_title"),
                    Path.of(row.text("pane_current_path")),
                    row.count("pane_pid"),
                    new PaneEdges(
                            row.flag("pane_at_top"),
                            row.flag("pane_at_bottom"),
                            row.flag("pane_at_left"),
                            row.flag("pane_at_right")),
                    floatingKnown ? Optional.of(row.flag(FLOATING)) : Optional.empty()));
        }
        List<ClientState> clients = new ArrayList<>();
        for (RowFormat.Row row : rows(CLIENTS, "list-clients")) {
            String session = row.text("session_id");
            clients.add(new ClientState(
                    row.text("client_name"),
                    session.isEmpty() ? Optional.empty() : Optional.of(new SessionId(session))));
        }
        return ServerSnapshot.of(Instant.now(), process.pid(), process.version(), sessions, windows, panes, clients);
    }

    /**
     * Runs one listing and reads its rows.
     *
     * <p>An empty server is not a failure: {@code list-sessions} reports "no server running" as a
     * nonzero exit, and a capture of nothing is still a capture.
     */
    private List<RowFormat.Row> rows(RowFormat format, String... command) {
        List<String> argv = new ArrayList<>(command.length + 2);
        argv.addAll(List.of(command));
        argv.add("-F");
        argv.add(format.template());
        CommandResult result = server.cmd(argv);
        if (!result.succeeded()) {
            if (result.stderr().stream().anyMatch(line -> line.contains("no server running"))) {
                return List.of();
            }
            throw new LibTmuxException("tmux " + command[0] + " failed: " + String.join("; ", result.stderr()));
        }
        return format.rows(result.stdout());
    }

    private static WindowContext context(RowFormat.Row row) {
        return new WindowContext(
                new SessionId(row.text("session_id")),
                new WindowIndex(row.number("window_index")),
                new WindowId(row.text("window_id")));
    }

    private static boolean serverAbsent(String message) {
        return message.contains("no server running")
                || message.contains("server exited unexpectedly")
                || message.contains("(No such file or directory)");
    }

    private static String[] withFloating() {
        String[] fields = Arrays.copyOf(PANE_FIELDS, PANE_FIELDS.length + 1);
        fields[PANE_FIELDS.length] = FLOATING;
        return fields;
    }

    record ServerProcess(long pid, TmuxVersion version) {}
}
