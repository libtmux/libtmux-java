package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.batch.OperationResult;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.query.TmuxFilters;
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
    private static final RowFormat PROCESS = RowFormat.of("pid", "version", "start_time");

    private final Server server;

    SnapshotCapture(Server server) {
        this.server = server;
    }

    /** One attempt, empty when the server was replaced under it. */
    Optional<ServerSnapshot> attempt() {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerUnavailableException("no tmux server is answering on this endpoint"));
        try {
            return Optional.of(capture(process));
        } catch (TargetGoneException replaced) {
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
     * into "no daemon" - used only by {@link SessionCreation#versionForCreation}, the one caller for which
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
            throw new MalformedResponseException(
                    server.config().binary() + " exited 0 and reported nothing for tmux's own identity; is it tmux?");
        }
        if (reported.size() != 1) {
            throw new MalformedResponseException("tmux did not report exactly one server identity row: " + reported);
        }
        RowFormat.Row row = reported.get(0);
        long pid = row.count("pid");
        if (pid <= 0) {
            throw new MalformedResponseException("tmux reported a malformed server pid: " + pid);
        }
        String version = row.text("version");
        long started = row.count("start_time");
        if (started < 0) {
            throw new MalformedResponseException("tmux reported a malformed server start time: " + started);
        }
        return Optional.of(new ServerProcess(pid, TmuxVersion.parse(version), started));
    }

    private static boolean daemonGenuinelyAbsent(String message) {
        return message.contains("no server running") || message.contains("(No such file or directory)");
    }

    /** The whole hierarchy in one invocation, fenced against the identity just read. */
    private ServerSnapshot capture(ServerProcess process) {
        boolean floatingKnown = process.version().atLeast(FLOATING_SINCE);
        RowFormat paneFormat = floatingKnown ? PANES_WITH_FLOATING : PANES;
        Batch listings = server.batch(process.pid(), process.startTime());
        listings.add(listing(SESSIONS, "list-sessions"));
        listings.add(listing(WINDOWS, "list-windows", "-a"));
        listings.add(listing(paneFormat, "list-panes", "-a"));
        listings.add(listing(CLIENTS, "list-clients"));
        List<OperationResult> answered = listings.run().operations();

        return consistent(() -> {
            List<SessionState> sessions =
                    sessionStates(rows(SESSIONS, answered.get(0), "list-sessions", process.version()));
            if (sessions.isEmpty()) {
                // A server with no sessions has no current target, so tmux refuses the rest of the
                // group. An empty sessions listing is the whole hierarchy, so there is nothing to read.
                return snapshotOf(process, sessions, List.of(), List.of(), List.of());
            }
            return snapshotOf(
                    process,
                    sessions,
                    windowStates(rows(WINDOWS, answered.get(1), "list-windows", process.version())),
                    paneStates(rows(paneFormat, answered.get(2), "list-panes", process.version()), floatingKnown),
                    clientStates(rows(CLIENTS, answered.get(3), "list-clients", process.version())));
        });
    }

    /**
     * Rows that parse but do not form one valid snapshot are tmux's failure. Only this conversion is
     * made: anything else thrown while reading is reported as itself.
     */
    private static <T> T consistent(java.util.function.Supplier<T> assembly) {
        try {
            return assembly.get();
        } catch (IllegalArgumentException inconsistent) {
            throw new MalformedResponseException(
                    "tmux returned listings that do not form one snapshot: " + inconsistent.getMessage(), inconsistent);
        }
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
                .orElseThrow(() -> new ServerUnavailableException("no tmux server is answering on this endpoint"));
        return hydrate(
                process,
                "#{==:#{" + field + "}," + target + "}",
                session -> field.equals("session_id")
                        ? session.id().value().equals(target)
                        : session.name().equals(target));
    }

    /**
     * The sessions stored under any name tmux may keep for this one, read in one fenced call, and
     * an empty capture when there are none. Empty when a candidate cannot sit inside a format, and
     * the caller reads the whole server instead.
     */
    Optional<ServerSnapshot> sessionsNamed(String name) {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerUnavailableException("no tmux server is answering on this endpoint"));
        List<String> names = TmuxFormats.storedNames(name, process.version());
        if (!names.stream().allMatch(TmuxFilters::literal)) {
            return Optional.empty();
        }
        String any = "0";
        for (String stored : names) {
            any = "#{||:#{==:#{session_name}," + stored + "}," + any + "}";
        }
        return Optional.of(hydrate(process, any, session -> names.contains(session.name()))
                .orElseGet(() -> snapshotOf(process, List.of(), List.of(), List.of(), List.of())));
    }

    /**
     * The sessions holding a row that {@code format} selects in this listing, read in full in one
     * fenced call. Empty when none does, which is also what a tmux that cannot evaluate the format
     * reports.
     *
     * <p>A window or pane format is lifted to its session by looping that session's windows and
     * panes, so the one call that reads the sessions also decides which ones to read.
     *
     * @param command {@code list-sessions}, {@code list-windows -a}, or {@code list-panes -a}
     */
    Optional<ServerSnapshot> sessionsWhere(String format, String... command) {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerUnavailableException("no tmux server is answering on this endpoint"));
        String holding =
                switch (command[0]) {
                    case "list-sessions" -> format;
                    case "list-windows" -> "#{W:#{?" + format + ",1,}}";
                    case "list-panes" -> "#{W:#{P:#{?" + format + ",1,}}}";
                    default -> throw new IllegalArgumentException("not a hierarchy listing: " + command[0]);
                };
        return hydrate(process, holding, session -> true);
    }

    /**
     * The sessions this filter selects, with their windows and panes, in one call fenced to {@code
     * process}. Every row tmux returns is validated before {@code keep} narrows them, so a malformed
     * or inconsistent row fails the read instead of hiding behind a miss.
     */
    private Optional<ServerSnapshot> hydrate(
            ServerProcess process, String filter, java.util.function.Predicate<SessionState> keep) {
        boolean floatingKnown = process.version().atLeast(FLOATING_SINCE);
        RowFormat paneFormat = floatingKnown ? PANES_WITH_FLOATING : PANES;
        Batch batch = server.batch(process.pid(), process.startTime());
        batch.add(listing(SESSIONS, "list-sessions", "-f", filter));
        batch.add(listing(WINDOWS, "list-windows", "-a", "-f", filter));
        batch.add(listing(paneFormat, "list-panes", "-a", "-f", filter));
        List<OperationResult> answered = batch.run().operations();
        return consistent(() -> kept(process, answered, paneFormat, floatingKnown, keep));
    }

    private Optional<ServerSnapshot> kept(
            ServerProcess process,
            List<OperationResult> answered,
            RowFormat paneFormat,
            boolean floatingKnown,
            java.util.function.Predicate<SessionState> keep) {
        List<SessionState> listed = sessionStates(rows(SESSIONS, answered.get(0), "list-sessions", process.version()));
        if (listed.isEmpty()) {
            // As in a full capture: with no session there is no current target, and tmux refuses
            // the rest of the group.
            return Optional.empty();
        }
        List<WindowState> windows = windowStates(rows(WINDOWS, answered.get(1), "list-windows", process.version()));
        List<PaneState> panes =
                paneStates(rows(paneFormat, answered.get(2), "list-panes", process.version()), floatingKnown);
        snapshotOf(process, listed, windows, panes, List.of());
        List<SessionState> sessions = listed.stream().filter(keep).toList();
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        java.util.Set<SessionId> ids =
                sessions.stream().map(SessionState::id).collect(java.util.stream.Collectors.toSet());
        return Optional.of(snapshotOf(
                process,
                sessions,
                windows.stream()
                        .filter(window -> ids.contains(window.context().session()))
                        .toList(),
                panes.stream()
                        .filter(pane -> ids.contains(pane.context().session()))
                        .toList(),
                List.of()));
    }

    /**
     * The sessions holding this pane, with their windows and panes, in one fenced call. Empty when no
     * session holds it.
     *
     * <p>The filter loops each listed row's own session, so every listing keeps whole sessions, and a
     * window linked into two sessions brings both. tmux evaluates this loop inside {@code -f} the same
     * way on every release from 3.2a; {@code docs/spikes/28-loop-filters.md} has the measurement.
     */
    Optional<ServerSnapshot> sessionsHolding(PaneId id) {
        ServerProcess process = process()
                .orElseThrow(() -> new ServerUnavailableException("no tmux server is answering on this endpoint"));
        return hydrate(process, "#{W:#{P:#{?#{==:#{pane_id}," + id.value() + "},1,}}}", session -> true);
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
    private List<RowFormat.Row> rows(RowFormat format, OperationResult operation, String command, TmuxVersion version) {
        if (operation.outcome() != OperationOutcome.COMPLETE) {
            throw server.failed(
                    command, operation.outcome().name().toLowerCase(Locale.ROOT).replace('_', ' '), operation.stderr());
        }
        return format.rows(TmuxFormats.printed(operation.stdout(), version));
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

    /** The server a capture reads, named by its process and when that process started. */
    record ServerProcess(long pid, TmuxVersion version, long startTime) {}

    private static ServerSnapshot snapshotOf(
            ServerProcess process,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        return ServerSnapshot.of(
                Instant.now(),
                process.pid(),
                process.startTime(),
                process.version(),
                sessions,
                windows,
                panes,
                clients);
    }
}
