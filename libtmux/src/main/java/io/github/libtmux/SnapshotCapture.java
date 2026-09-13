package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.batch.OperationResult;
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
 * <p>Two commands: who the server is, then the listings as one group fenced against both halves of
 * that answer, its pid and its version. tmux runs a group in the server, so rows cannot come from
 * two of them, and a server that is not the one probed is refused before a listing runs rather than
 * detected afterwards. Retrying is {@link Server#snapshot()}'s decision, not this one's.
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
        ServerProcess process =
                process().orElseThrow(() -> new LibTmuxException("no tmux server is answering on this endpoint"));
        try {
            return Optional.of(capture(process));
        } catch (ObjectDoesNotExist replaced) {
            // The fence answered: this is no longer the server the identity came from.
            return Optional.empty();
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

    /** The whole hierarchy in one invocation, fenced against the identity just read. */
    private ServerSnapshot capture(ServerProcess process) {
        boolean floatingKnown = process.version().atLeast(FLOATING_SINCE);
        RowFormat paneFormat = floatingKnown ? PANES_WITH_FLOATING : PANES;
        Batch listings = server.batch(process.pid(), process.version());
        listings.add(listing(SESSIONS, "list-sessions"));
        listings.add(listing(WINDOWS, "list-windows", "-a"));
        listings.add(listing(paneFormat, "list-panes", "-a"));
        listings.add(listing(CLIENTS, "list-clients"));
        List<OperationResult> answered = listings.run().operations();

        List<SessionState> sessions = new ArrayList<>();
        for (RowFormat.Row row : rows(SESSIONS, answered.get(0), "list-sessions")) {
            sessions.add(new SessionState(
                    new SessionId(row.text("session_id")),
                    row.text("session_name"),
                    row.count("session_attached") > 0,
                    row.number("session_windows")));
        }
        if (sessions.isEmpty()) {
            // A server with no sessions has no current target, so tmux refuses the rest of the
            // group. An empty sessions listing is the whole hierarchy, so there is nothing to read.
            return ServerSnapshot.of(
                    Instant.now(), process.pid(), process.version(), sessions, List.of(), List.of(), List.of());
        }
        List<WindowState> windows = new ArrayList<>();
        for (RowFormat.Row row : rows(WINDOWS, answered.get(1), "list-windows")) {
            windows.add(new WindowState(
                    context(row),
                    row.text("window_name"),
                    row.flag("window_active"),
                    row.number("window_panes"),
                    row.flag("window_linked"),
                    new Dimensions(row.number("window_width"), row.number("window_height")),
                    row.text("window_layout")));
        }
        List<PaneState> panes = new ArrayList<>();
        for (RowFormat.Row row : rows(paneFormat, answered.get(2), "list-panes")) {
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
        for (RowFormat.Row row : rows(CLIENTS, answered.get(3), "list-clients")) {
            String session = row.text("session_id");
            clients.add(new ClientState(
                    row.text("client_name"),
                    session.isEmpty() ? Optional.empty() : Optional.of(new SessionId(session))));
        }
        return ServerSnapshot.of(Instant.now(), process.pid(), process.version(), sessions, windows, panes, clients);
    }

    private static List<String> listing(RowFormat format, String... command) {
        List<String> argv = new ArrayList<>(command.length + 2);
        argv.addAll(List.of(command));
        argv.add("-F");
        argv.add(format.template());
        return argv;
    }

    /** Reads one listing's rows, insisting tmux actually ran it. */
    private static List<RowFormat.Row> rows(RowFormat format, OperationResult operation, String command) {
        if (operation.outcome() != OperationOutcome.COMPLETE) {
            throw new LibTmuxException("tmux " + command + " failed: " + String.join("; ", operation.stderr()));
        }
        return format.rows(operation.stdout());
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
