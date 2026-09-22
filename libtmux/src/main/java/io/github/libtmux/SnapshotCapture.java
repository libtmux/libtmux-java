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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;

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
        "pane_left",
        "pane_top",
        "pane_title",
        "pane_current_path",
        "pane_pid",
        "pane_at_top",
        "pane_at_bottom",
        "pane_at_left",
        "pane_at_right"
    };

    private static final RowFormat PANES = RowFormat.of(PANE_FIELDS);

    /**
     * tmux gained {@code pane_floating_flag} at {@code 87aaff5f} ("Bring some new formats from the
     * floating panes work"), which {@code git tag --contains} places on 3.7 and nothing earlier;
     * before that the format expands to nothing, indistinguishable from a real pane answering false.
     * Not probeable: an unknown format variable and a false one both expand empty, and {@link
     * Commands#list} lists commands, not the format variables a running tmux understands.
     */
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
        ServerProcess process = process()
                .orElseThrow(() -> new ServerNotRunningException("no tmux server is answering on this endpoint"));
        try {
            return Optional.of(capture(process));
        } catch (ObjectDoesNotExistException replaced) {
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
        return identity(Server::serverAbsent);
    }

    /**
     * As {@link #process}, except a live daemon this client cannot actually talk to is never folded
     * into "no daemon" - used only by {@link Server#versionForCreation}, the one caller for which
     * that distinction matters.
     *
     * <p>tmux reports both the same way from a client's side: "no server running"/"(No such file or
     * directory)" for a socket nothing is listening on, and "server exited unexpectedly" for a
     * daemon that refused this client's handshake - confirmed against the matrix, a 3.2a client
     * against a 3.7c daemon on the same socket fails the second way, rc 1. {@link #process} still
     * folds both into "no daemon" for every other caller, which asks "is there a server I can read
     * from" and for which a daemon this client cannot use is no more usable than none at all.
     */
    Optional<ServerProcess> processForCreation() {
        return identity(SnapshotCapture::daemonGenuinelyAbsent);
    }

    private Optional<ServerProcess> identity(Predicate<String> absent) {
        CommandResult result = server.cmd("display-message", "-p", PROCESS.template());
        if (!result.succeeded()) {
            if (result.stderr().stream().anyMatch(absent)) {
                return Optional.empty();
            }
            throw server.failed("display-message", result);
        }
        List<RowFormat.Row> reported = PROCESS.rows(result.stdout());
        if (reported.isEmpty()) {
            throw new LibTmuxException(
                    server.config().binary() + " exited 0 and reported nothing for tmux's own identity; is it tmux?");
        }
        if (reported.size() != 1) {
            throw new LibTmuxException("tmux did not report exactly one server identity row: " + reported);
        }
        RowFormat.Row row = reported.get(0);
        long pid = row.count("pid");
        if (pid <= 0) {
            throw new LibTmuxException("tmux reported a malformed server pid: " + pid);
        }
        String version = row.text("version");
        return Optional.of(new ServerProcess(pid, TmuxVersion.parse(version), version));
    }

    private static boolean daemonGenuinelyAbsent(String message) {
        return message.contains("no server running") || message.contains("(No such file or directory)");
    }

    /** The whole hierarchy in one invocation, fenced against the identity just read. */
    private ServerSnapshot capture(ServerProcess process) {
        boolean floatingKnown = process.version().atLeast(FLOATING_SINCE);
        RowFormat paneFormat = floatingKnown ? PANES_WITH_FLOATING : PANES;
        Batch listings = server.batch(process.pid(), process.reported());
        listings.add(listing(SESSIONS, "list-sessions"));
        listings.add(listing(WINDOWS, "list-windows", "-a"));
        listings.add(listing(paneFormat, "list-panes", "-a"));
        listings.add(listing(CLIENTS, "list-clients"));
        List<OperationResult> answered = listings.run().operations();

        List<SessionState> sessions = sessionStates(rows(SESSIONS, answered.get(0), "list-sessions"));
        if (sessions.isEmpty()) {
            // A server with no sessions has no current target, so tmux refuses the rest of the
            // group. An empty sessions listing is the whole hierarchy, so there is nothing to read.
            return ServerSnapshot.of(
                    Instant.now(), process.pid(), process.version(), sessions, List.of(), List.of(), List.of());
        }
        return ServerSnapshot.of(
                Instant.now(),
                process.pid(),
                process.version(),
                sessions,
                windowStates(rows(WINDOWS, answered.get(1), "list-windows")),
                paneStates(rows(paneFormat, answered.get(2), "list-panes"), floatingKnown),
                clientStates(rows(CLIENTS, answered.get(3), "list-clients")));
    }

    /**
     * One session, its windows, and its panes. Empty when that session is absent. Other sessions are
     * not read.
     *
     * @param target the session name or id passed to {@code -t}
     * @param field {@code session_name} or {@code session_id}
     */
    Optional<ServerSnapshot> oneSession(String target, String field) {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerNotRunningException("no tmux server is answering on this endpoint"));
        String filter = "#{==:#{" + field + "}," + target + "}";
        boolean floatingKnown = process.version().atLeast(FLOATING_SINCE);
        RowFormat paneFormat = floatingKnown ? PANES_WITH_FLOATING : PANES;
        Batch batch = server.batch(process.pid(), process.reported());
        batch.add(listing(SESSIONS, "list-sessions", "-f", filter));
        batch.add(listing(WINDOWS, "list-windows", "-a", "-f", filter));
        batch.add(listing(paneFormat, "list-panes", "-a", "-f", filter));
        List<OperationResult> answered = batch.run().operations();
        List<SessionState> sessions = sessionStates(rows(SESSIONS, answered.get(0), "list-sessions")).stream()
                .filter(session -> field.equals("session_id")
                        ? session.id().value().equals(target)
                        : session.name().equals(target))
                .toList();
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        java.util.Set<SessionId> ids =
                sessions.stream().map(SessionState::id).collect(java.util.stream.Collectors.toSet());
        return Optional.of(ServerSnapshot.of(
                Instant.now(),
                process.pid(),
                process.version(),
                sessions,
                windowStates(rows(WINDOWS, answered.get(1), "list-windows")).stream()
                        .filter(window -> ids.contains(window.context().session()))
                        .toList(),
                paneStates(rows(paneFormat, answered.get(2), "list-panes"), floatingKnown).stream()
                        .filter(pane -> ids.contains(pane.context().session()))
                        .toList(),
                List.of()));
    }

    /**
     * The sessions a filtered listing names, each read in full. Empty when the probe finds nothing,
     * which is also what a server that ignores {@code -f} reports.
     *
     * @param command the listing, such as {@code list-sessions} or {@code list-panes -a}
     */
    Optional<ServerSnapshot> sessionsWhere(String format, String... command) {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerNotRunningException("no tmux server is answering on this endpoint"));
        RowFormat sessionOnly = RowFormat.of("session_id");
        List<String> argv = new ArrayList<>();
        argv.addAll(List.of(command));
        argv.add("-f");
        argv.add(format);
        Batch probe = server.batch(process.pid(), process.reported());
        probe.add(listing(sessionOnly, argv.toArray(String[]::new)));
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        for (RowFormat.Row row : rows(sessionOnly, probe.run().operations().get(0), command[0])) {
            String id = row.text("session_id");
            if (!id.isEmpty()) {
                sessionIds.add(id);
            }
        }
        if (sessionIds.isEmpty()) {
            return Optional.empty();
        }
        List<SessionState> sessions = new ArrayList<>();
        List<WindowState> windows = new ArrayList<>();
        List<PaneState> panes = new ArrayList<>();
        for (String id : sessionIds) {
            Optional<ServerSnapshot> one = oneSession(id, "session_id");
            if (one.isEmpty()) {
                continue;
            }
            ServerSnapshot captured = one.get();
            sessions.addAll(captured.sessions());
            windows.addAll(captured.windows());
            panes.addAll(captured.panes());
        }
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ServerSnapshot.of(
                Instant.now(), process.pid(), process.version(), sessions, windows, panes, List.of()));
    }

    /** The session that owns this pane, without listing every pane first. */
    Optional<SessionId> sessionOfPane(PaneId id) {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerNotRunningException("no tmux server is answering on this endpoint"));
        RowFormat sessionOnly = RowFormat.of("session_id");
        Batch probe = server.batch(process.pid(), process.reported());
        probe.add(listing(sessionOnly, "list-panes", "-a", "-f", "#{==:#{pane_id}," + id.value() + "}"));
        OperationResult answered = probe.run().operations().get(0);
        if (answered.outcome() != OperationOutcome.COMPLETE) {
            if (String.join("\n", answered.stderr()).contains("no current target")) {
                return Optional.empty();
            }
        }
        List<RowFormat.Row> found;
        try {
            found = rows(sessionOnly, answered, "list-panes");
        } catch (io.github.libtmux.format.TmuxFormatException ignored) {
            return Optional.empty();
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SessionId(found.get(0).text("session_id")));
    }

    private static List<SessionState> sessionStates(List<RowFormat.Row> rows) {
        List<SessionState> sessions = new ArrayList<>();
        for (RowFormat.Row row : rows) {
            sessions.add(new SessionState(
                    new SessionId(row.text("session_id")),
                    row.text("session_name"),
                    row.count("session_attached") > 0,
                    row.number("session_windows")));
        }
        return sessions;
    }

    private static List<WindowState> windowStates(List<RowFormat.Row> rows) {
        List<WindowState> windows = new ArrayList<>();
        for (RowFormat.Row row : rows) {
            windows.add(new WindowState(
                    context(row),
                    row.text("window_name"),
                    row.flag("window_active"),
                    row.number("window_panes"),
                    row.flag("window_linked"),
                    new Dimensions(row.number("window_width"), row.number("window_height")),
                    row.text("window_layout")));
        }
        return windows;
    }

    private static List<PaneState> paneStates(List<RowFormat.Row> rows, boolean floatingKnown) {
        List<PaneState> panes = new ArrayList<>();
        for (RowFormat.Row row : rows) {
            panes.add(new PaneState(
                    context(row),
                    new PaneId(row.text("pane_id")),
                    row.number("pane_index"),
                    row.flag("pane_active"),
                    row.text("pane_current_command"),
                    new Dimensions(row.number("pane_width"), row.number("pane_height")),
                    new PanePosition(row.number("pane_left"), row.number("pane_top")),
                    row.text("pane_title"),
                    row.text("pane_current_path"),
                    panePid(row),
                    new PaneEdges(
                            row.flag("pane_at_top"),
                            row.flag("pane_at_bottom"),
                            row.flag("pane_at_left"),
                            row.flag("pane_at_right")),
                    floatingKnown ? Optional.of(row.flag(FLOATING)) : Optional.empty()));
        }
        return panes;
    }

    private static List<ClientState> clientStates(List<RowFormat.Row> rows) {
        List<ClientState> clients = new ArrayList<>();
        for (RowFormat.Row row : rows) {
            String session = row.text("session_id");
            clients.add(new ClientState(
                    row.text("client_name"),
                    session.isEmpty() ? Optional.empty() : Optional.of(new SessionId(session))));
        }
        return clients;
    }

    /**
     * A pane with no process reports {@code pane_pid} as {@code 0} on every released tmux through
     * 3.7c, and as an empty string on the built development tmux this port has no CI lane for. That
     * development tmux reports the same empty string for a pane that ran a real process and then
     * died with {@code remain-on-exit} — the two are indistinguishable from this field alone there;
     * {@link Pane#dead()} is the live read that tells them apart. Either raw form collapses to
     * {@link OptionalLong#empty()} rather than a literal {@code 0}, so a caller cannot mistake the
     * sentinel for a real pid.
     */
    private static OptionalLong panePid(RowFormat.Row row) {
        if (row.text("pane_pid").isEmpty()) {
            return OptionalLong.empty();
        }
        long pid = row.count("pane_pid");
        return pid == 0 ? OptionalLong.empty() : OptionalLong.of(pid);
    }

    private static List<String> listing(RowFormat format, String... command) {
        List<String> argv = new ArrayList<>(command.length + 2);
        argv.addAll(List.of(command));
        argv.add("-F");
        argv.add(format.template());
        return argv;
    }

    /** Reads one listing's rows, insisting tmux actually ran it. */
    private List<RowFormat.Row> rows(RowFormat format, OperationResult operation, String command) {
        if (operation.outcome() != OperationOutcome.COMPLETE) {
            throw server.failed(
                    command, operation.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' '), operation.stderr());
        }
        return format.rows(operation.stdout());
    }

    private static WindowContext context(RowFormat.Row row) {
        return new WindowContext(
                new SessionId(row.text("session_id")),
                new WindowIndex(row.number("window_index")),
                new WindowId(row.text("window_id")));
    }

    private static String[] withFloating() {
        String[] fields = Arrays.copyOf(PANE_FIELDS, PANE_FIELDS.length + 1);
        fields[PANE_FIELDS.length] = FLOATING;
        return fields;
    }

    /**
     * @param reported the version exactly as tmux wrote it, which is what the capture's fence
     *     compares — not the parsed version's text, which need not be byte for byte the same
     */
    record ServerProcess(long pid, TmuxVersion version, String reported) {}
}
