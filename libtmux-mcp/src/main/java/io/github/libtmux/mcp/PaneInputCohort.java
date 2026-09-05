package io.github.libtmux.mcp;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.batch.BatchResult;
import io.github.libtmux.batch.OperationResult;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.format.TmuxFormatException;
import io.github.libtmux.transport.CommandResult;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One authoritative view of the panes tmux may receive input through. */
final class PaneInputCohort {

    private static final long MAX_TMUX_PANE_ID = 4_294_967_295L;

    private static final RowFormat PANES =
            RowFormat.of("pane_id", "pane_synchronized", "pane_in_mode", "pane_dead", "pane_current_command");

    private static final RowFormat CLIENTS = RowFormat.of("client_control_mode", "pane_id", "window_zoomed_flag");

    private PaneInputCohort() {}

    static Resolution resolve(Pane source) {
        return resolve(source, Caller.nowhere());
    }

    static Resolution resolve(Pane source, Caller caller) {
        BatchResult snapshot = source.server()
                .batch()
                .add(List.of("list-panes", "-t", source.id().value(), "-F", PANES.template()))
                .add(List.of("list-clients", "-F", CLIENTS.template()))
                .run();
        if (snapshot.operations().size() != 2) {
            throw new LibTmuxException("tmux returned an incomplete pane input snapshot");
        }
        return parse(
                source.id().value(),
                result(snapshot.operations().get(0)),
                result(snapshot.operations().get(1)),
                caller);
    }

    static Resolution parse(String sourcePaneId, CommandResult answer) {
        return parse(sourcePaneId, answer, new CommandResult(0, List.of(), List.of()), Caller.nowhere());
    }

    static Resolution parse(String sourcePaneId, CommandResult answer, CommandResult clientAnswer, Caller caller) {
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
        for (RowFormat.Row row : rows) {
            Member member = member(row);
            if (members.putIfAbsent(member.paneId(), member) != null) {
                throw new LibTmuxException("tmux returned duplicate pane input state");
            }
        }
        Member source = members.get(sourcePaneId);
        if (source == null) {
            throw new LibTmuxException("tmux returned no pane input state for " + sourcePaneId);
        }
        List<Member> recipients = source.synchronizedPane()
                ? members.values().stream()
                        .filter(Member::synchronizedPane)
                        .sorted(java.util.Comparator.comparing(Member::paneId))
                        .toList()
                : List.of(source);
        Set<String> attended = attended(clientAnswer, members);
        return new Resolution(source, recipients, caller, attended);
    }

    private static CommandResult result(OperationResult operation) {
        return new CommandResult(operation.succeeded() ? 0 : 1, operation.stdout(), operation.stderr());
    }

    private static Set<String> attended(CommandResult answer, Map<String, Member> members) {
        if (!answer.succeeded()) {
            throw new LibTmuxException("tmux could not resolve client attention state");
        }
        if (answer.stdout().isEmpty()) {
            return Set.of();
        }
        int terminators = validateFraming(CLIENTS, answer.stdout());
        List<RowFormat.Row> rows = CLIENTS.rows(answer.stdout());
        if (rows.size() != terminators) {
            throw new TmuxFormatException("tmux returned an incomplete client attention listing");
        }
        Set<String> attended = new LinkedHashSet<>();
        for (RowFormat.Row row : rows) {
            boolean controlMode = row.flag("client_control_mode");
            String activePane = paneId(row.text("pane_id"));
            boolean zoomed = row.flag("window_zoomed_flag");
            if (controlMode || !members.containsKey(activePane)) {
                continue;
            }
            if (zoomed) {
                attended.add(activePane);
            } else {
                attended.addAll(members.keySet());
            }
        }
        return Set.copyOf(attended);
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
        return new Member(paneId, synchronizedPane, mode, rawMode.equals("0"), dead, command);
    }

    private static String paneId(String value) {
        if (!value.startsWith("%") || value.length() == 1) {
            throw new TmuxFormatException("pane_id was invalid");
        }
        try {
            long id = Long.parseLong(value.substring(1));
            if (id < 0 || id > MAX_TMUX_PANE_ID || !value.equals("%" + id)) {
                throw new TmuxFormatException("pane_id was invalid");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new TmuxFormatException("pane_id was invalid", failure);
        }
    }

    record Member(
            String paneId,
            boolean synchronizedPane,
            long mode,
            boolean canonicalZeroMode,
            boolean dead,
            String currentCommand) {

        boolean writable() {
            return mode == 0 && canonicalZeroMode;
        }
    }

    record Resolution(Member source, List<Member> keyRecipients, Caller caller, Set<String> attendedPaneIds) {

        Resolution {
            keyRecipients = List.copyOf(keyRecipients);
            attendedPaneIds = Set.copyOf(attendedPaneIds);
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
            if (!member.writable()) {
                throw new IllegalStateException(
                        operation + " refuses pane " + member.paneId() + " while it is in a human-owned mode");
            }
        }
    }
}
