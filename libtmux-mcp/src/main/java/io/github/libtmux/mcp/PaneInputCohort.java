package io.github.libtmux.mcp;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.ServerIdentity;
import io.github.libtmux.batch.BatchResult;
import io.github.libtmux.batch.OperationResult;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.format.TmuxFormatException;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One authoritative view of the panes tmux may receive input through. */
final class PaneInputCohort {

    private static final long MAX_TMUX_ID_COMPONENT = 4_294_967_295L;

    private static final RowFormat PANES = RowFormat.of(
            "pane_id",
            "pane_synchronized",
            "pane_in_mode",
            "pane_dead",
            "pane_current_command",
            "pane_input_off",
            "session_id",
            "window_id",
            "window_index",
            "pid",
            "start_time",
            "socket_path");

    private static final RowFormat CLIENTS = RowFormat.of(
            "client_control_mode", "session_id", "window_id", "window_index", "pane_id", "window_zoomed_flag");

    private static final Comparator<ClientPlacement> CLIENT_ORDER = Comparator.comparing(ClientPlacement::sessionId)
            .thenComparing(ClientPlacement::windowId)
            .thenComparingLong(ClientPlacement::windowIndex)
            .thenComparing(ClientPlacement::paneId)
            .thenComparingInt(client -> client.zoomed() ? 1 : 0);

    private PaneInputCohort() {}

    static Resolution resolve(Pane source) {
        return resolve(source, Caller.nowhere());
    }

    static Resolution resolve(Pane source, Caller caller) {
        ServerIdentity identity = source.server().identity();
        BatchResult snapshot = source.server()
                .batch()
                .add(List.of("list-panes", "-a", "-F", PANES.template()))
                .add(List.of("list-clients", "-F", CLIENTS.template()))
                .run();
        if (snapshot.operations().size() != 2) {
            throw new LibTmuxException("tmux returned an incomplete pane input snapshot");
        }
        return parse(
                identity.realm(),
                source.id().value(),
                result(snapshot.operations().get(0)),
                result(snapshot.operations().get(1)),
                caller);
    }

    static Resolution parse(String sourcePaneId, CommandResult answer) {
        return parse("test", sourcePaneId, answer, new CommandResult(0, List.of(), List.of()), Caller.nowhere());
    }

    static Resolution parse(String sourcePaneId, CommandResult answer, CommandResult clientAnswer, Caller caller) {
        return parse("test", sourcePaneId, answer, clientAnswer, caller);
    }

    private static Resolution parse(
            String realm, String sourcePaneId, CommandResult answer, CommandResult clientAnswer, Caller caller) {
        PaneSnapshot paneSnapshot = panes(realm, sourcePaneId, answer);
        Map<String, Member> members = paneSnapshot.members();
        Authority generation = paneSnapshot.authority();
        Member source = members.get(sourcePaneId);
        if (source == null) {
            throw new LibTmuxException("tmux returned no pane input state for " + sourcePaneId);
        }
        Map<String, Member> windowMembers = members.values().stream()
                .filter(member -> member.windowId().equals(source.windowId()))
                .collect(java.util.stream.Collectors.toMap(
                        Member::paneId,
                        java.util.function.Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<Member> recipients = source.synchronizedPane()
                ? windowMembers.values().stream()
                        .filter(Member::synchronizedPane)
                        .sorted(java.util.Comparator.comparing(Member::paneId))
                        .toList()
                : List.of(source);
        ClientSnapshot clients = clients(clientAnswer, members, source.windowId());
        caller.requireConsistent(
                generation.serverPid(),
                generation.socketPath(),
                members.values().stream()
                        .collect(java.util.stream.Collectors.toMap(Member::paneId, Member::sessionIds)));
        return new Resolution(
                generation, source, recipients, caller, clients.attendedPaneIds(), clients.terminalClients());
    }

    static Presence presence(Pane pane, Authority expected) {
        try {
            CommandResult answer = pane.server().cmd("list-panes", "-a", "-F", PANES.template());
            PaneSnapshot snapshot =
                    panes(pane.server().identity().realm(), pane.id().value(), answer);
            Authority observed = snapshot.authority();
            if (!observed.equals(expected)) {
                return Presence.GONE;
            }
            Member target = snapshot.members().get(pane.id().value());
            return target == null || target.dead() ? Presence.GONE : Presence.PRESENT;
        } catch (RuntimeException failure) {
            return Presence.UNKNOWN;
        }
    }

    private static PaneSnapshot panes(String realm, String sourcePaneId, CommandResult answer) {
        if (!answer.succeeded()) {
            throw new LibTmuxException("tmux could not resolve pane input state");
        }
        if (answer.stdout().isEmpty()) {
            throw new LibTmuxException("tmux returned no pane input state for " + sourcePaneId);
        }
        int terminators = validateFraming(PANES, answer.stdout());
        List<RowFormat.Row> rows = PANES.rows(answer.stdout());
        if (rows.size() != terminators) {
            throw new TmuxFormatException("tmux returned an incomplete pane input listing");
        }

        Map<String, Member> members = new LinkedHashMap<>();
        Authority authority = null;
        for (RowFormat.Row row : rows) {
            Authority observed = authority(realm, row);
            if (authority != null && !authority.equals(observed)) {
                throw new TmuxFormatException("tmux returned pane rows from different server generations");
            }
            authority = observed;
            Member member = member(row);
            Member prior = members.get(member.paneId());
            if (prior == null) {
                members.put(member.paneId(), member);
            } else if (!prior.samePane(member)) {
                throw new LibTmuxException("tmux returned inconsistent duplicate pane input state");
            } else if (prior.placements().containsAll(member.placements())) {
                throw new LibTmuxException("tmux returned a duplicate pane placement");
            } else {
                members.put(member.paneId(), prior.withPlacements(member.placements()));
            }
        }
        Map<String, Set<Placement>> windowPlacements = new LinkedHashMap<>();
        for (Member member : members.values()) {
            Set<Placement> prior = windowPlacements.putIfAbsent(member.windowId(), member.placements());
            if (prior != null && !prior.equals(member.placements())) {
                throw new TmuxFormatException("tmux returned incomplete linked-window pane placements");
            }
        }
        return new PaneSnapshot(java.util.Objects.requireNonNull(authority), Map.copyOf(members));
    }

    private static CommandResult result(OperationResult operation) {
        return new CommandResult(operation.succeeded() ? 0 : 1, operation.stdout(), operation.stderr());
    }

    private static ClientSnapshot clients(CommandResult answer, Map<String, Member> members, String sourceWindowId) {
        if (!answer.succeeded()) {
            throw new LibTmuxException("tmux could not resolve client attention state");
        }
        if (answer.stdout().isEmpty()) {
            return new ClientSnapshot(List.of(), Set.of());
        }
        int terminators = validateFraming(CLIENTS, answer.stdout());
        List<RowFormat.Row> rows = CLIENTS.rows(answer.stdout());
        if (rows.size() != terminators) {
            throw new TmuxFormatException("tmux returned an incomplete client attention listing");
        }
        Set<String> attended = new LinkedHashSet<>();
        List<ClientPlacement> terminalClients = new ArrayList<>();
        for (RowFormat.Row row : rows) {
            boolean controlMode = row.flag("client_control_mode");
            if (controlMode) {
                continue;
            }
            String clientSession = targetId(row.text("session_id"), '$', "session_id");
            String clientWindow = targetId(row.text("window_id"), '@', "window_id");
            long clientWindowIndex = unsignedCanonical(row.text("window_index"), "window_index");
            String activePane = paneId(row.text("pane_id"));
            boolean zoomed = row.flag("window_zoomed_flag");
            Member active = members.get(activePane);
            if (active == null) {
                throw new TmuxFormatException("a terminal client reported an unknown active pane");
            }
            if (!active.placements().contains(new Placement(clientSession, clientWindow, clientWindowIndex))) {
                throw new TmuxFormatException("a terminal client reported inconsistent active pane placement");
            }
            terminalClients.add(
                    new ClientPlacement(clientSession, clientWindow, clientWindowIndex, activePane, zoomed));
            if (!active.windowId().equals(sourceWindowId)) {
                continue;
            }
            if (zoomed) {
                attended.add(activePane);
            } else {
                members.values().stream()
                        .filter(member -> member.windowId().equals(sourceWindowId))
                        .map(Member::paneId)
                        .forEach(attended::add);
            }
        }
        terminalClients.sort(CLIENT_ORDER);
        return new ClientSnapshot(List.copyOf(terminalClients), Set.copyOf(attended));
    }

    private static int validateFraming(RowFormat format, List<String> lines) {
        String terminator = format.template().substring(format.template().lastIndexOf('}') + 1);
        int closed = 0;
        for (String line : lines) {
            int marker = line.indexOf(terminator);
            if (marker < 0) {
                continue;
            }
            if (marker + terminator.length() != line.length()
                    || line.indexOf(terminator, marker + terminator.length()) >= 0) {
                throw new TmuxFormatException("tmux returned a malformed pane input row terminator");
            }
            closed++;
        }
        if (closed == 0) {
            throw new TmuxFormatException("tmux returned no terminated pane input rows");
        }
        return closed;
    }

    private static Member member(RowFormat.Row row) {
        String paneId = paneId(row.text("pane_id"));
        boolean synchronizedPane = row.flag("pane_synchronized");
        String rawMode = row.text("pane_in_mode");
        long mode = row.count("pane_in_mode");
        if (mode < 0) {
            throw new TmuxFormatException("pane_in_mode was negative");
        }
        boolean dead = row.flag("pane_dead");
        String command = row.text("pane_current_command");
        if (command.isEmpty()) {
            throw new TmuxFormatException("pane_current_command was empty");
        }
        boolean inputDisabled = row.flag("pane_input_off");
        String sessionId = targetId(row.text("session_id"), '$', "session_id");
        String windowId = targetId(row.text("window_id"), '@', "window_id");
        long windowIndex = unsignedCanonical(row.text("window_index"), "window_index");
        return new Member(
                paneId,
                synchronizedPane,
                mode,
                rawMode.equals("0"),
                dead,
                command,
                inputDisabled,
                windowId,
                Set.of(new Placement(sessionId, windowId, windowIndex)));
    }

    private static Authority authority(String realm, RowFormat.Row row) {
        long pid = positiveCanonical(row.text("pid"), "pid");
        long started = positiveCanonical(row.text("start_time"), "start_time");
        String socket = row.text("socket_path");
        try {
            RouteValue.requireSafe(socket, "tmux socket path");
            if (socket.isBlank() || !java.nio.file.Path.of(socket).isAbsolute()) {
                throw new TmuxFormatException("socket_path was not absolute");
            }
        } catch (IllegalArgumentException failure) {
            throw new TmuxFormatException("socket_path was invalid", failure);
        }
        return new Authority(realm, socket, pid, started);
    }

    private static long positiveCanonical(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || !value.equals(Long.toString(parsed))) {
                throw new TmuxFormatException(field + " was not a canonical positive number");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new TmuxFormatException(field + " was not a canonical positive number", failure);
        }
    }

    private static String paneId(String value) {
        return targetId(value, '%', "pane_id");
    }

    private static String targetId(String value, char sigil, String field) {
        if (value.length() == 1 || value.isEmpty() || value.charAt(0) != sigil) {
            throw new TmuxFormatException(field + " was invalid");
        }
        unsignedCanonical(value.substring(1), field);
        return value;
    }

    private static long unsignedCanonical(String value, String field) {
        try {
            long id = Long.parseLong(value);
            if (id < 0 || id > MAX_TMUX_ID_COMPONENT || !value.equals(Long.toString(id))) {
                throw new TmuxFormatException(field + " was invalid");
            }
            return id;
        } catch (NumberFormatException failure) {
            throw new TmuxFormatException(field + " was invalid", failure);
        }
    }

    record Authority(String realm, String socketPath, long serverPid, long startTime) {}

    enum Presence {
        PRESENT,
        GONE,
        UNKNOWN
    }

    private record PaneSnapshot(Authority authority, Map<String, Member> members) {}

    private record ClientSnapshot(List<ClientPlacement> terminalClients, Set<String> attendedPaneIds) {}

    record Placement(String sessionId, String windowId, long windowIndex) {}

    record ClientPlacement(String sessionId, String windowId, long windowIndex, String paneId, boolean zoomed) {}

    record Member(
            String paneId,
            boolean synchronizedPane,
            long mode,
            boolean canonicalZeroMode,
            boolean dead,
            String currentCommand,
            boolean inputDisabled,
            String windowId,
            Set<Placement> placements) {

        Member {
            placements = Set.copyOf(placements);
        }

        Set<String> sessionIds() {
            return placements.stream()
                    .map(Placement::sessionId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        boolean writable() {
            return mode == 0 && canonicalZeroMode;
        }

        boolean samePane(Member other) {
            return paneId.equals(other.paneId)
                    && synchronizedPane == other.synchronizedPane
                    && mode == other.mode
                    && canonicalZeroMode == other.canonicalZeroMode
                    && dead == other.dead
                    && currentCommand.equals(other.currentCommand)
                    && inputDisabled == other.inputDisabled
                    && windowId.equals(other.windowId);
        }

        Member withPlacements(Set<Placement> more) {
            Set<Placement> merged = new LinkedHashSet<>(placements);
            merged.addAll(more);
            return new Member(
                    paneId,
                    synchronizedPane,
                    mode,
                    canonicalZeroMode,
                    dead,
                    currentCommand,
                    inputDisabled,
                    windowId,
                    merged);
        }
    }

    record Resolution(
            Authority authority,
            Member source,
            List<Member> keyRecipients,
            Caller caller,
            Set<String> attendedPaneIds,
            List<ClientPlacement> terminalClients) {

        Resolution {
            keyRecipients = List.copyOf(keyRecipients);
            attendedPaneIds = Set.copyOf(attendedPaneIds);
            terminalClients = List.copyOf(terminalClients);
        }

        List<String> configuredKeyRecipientIds() {
            return keyRecipients.stream().map(Member::paneId).toList();
        }

        List<String> requireKeyRecipients(String operation) {
            keyRecipients.forEach(member -> requireWritable(operation, member));
            return configuredKeyRecipientIds();
        }

        void requirePasteTarget(String operation) {
            requireWritable(operation, source);
        }

        String requireSingularCommandPane(String operation) {
            keyRecipients.forEach(member -> requireWritable(operation, member));
            if (keyRecipients.size() != 1) {
                throw new IllegalStateException(
                        operation + " requires exactly one effective pane; observed " + configuredKeyRecipientIds());
            }
            return source.currentCommand();
        }

        private void requireWritable(String operation, Member member) {
            if (caller.uncertain()) {
                throw new IllegalStateException(operation + " refuses input while caller identity is unavailable");
            }
            if (caller.isSelf(new PaneId(member.paneId()))) {
                throw new IllegalStateException(operation + " refuses caller pane " + member.paneId());
            }
            if (attendedPaneIds.contains(member.paneId())) {
                throw new IllegalStateException(operation + " refuses attended pane " + member.paneId());
            }
            if (member.dead()) {
                throw new IllegalStateException(operation + " refuses dead pane " + member.paneId());
            }
            if (member.inputDisabled()) {
                throw new IllegalStateException(operation + " refuses input-disabled pane " + member.paneId());
            }
            if (!member.writable()) {
                throw new IllegalStateException(
                        operation + " refuses pane " + member.paneId() + " while it is in a human-owned mode");
            }
        }
    }
}
