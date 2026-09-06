package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Process-wide ownership of the panes an MCP input operation may reach. */
final class PaneInputReservations {

    private static final Object MONITOR = new Object();
    private static final Set<PaneKey> HELD = new HashSet<>();

    private PaneInputReservations() {}

    static Lease keys(PaneInputCohort.Resolution initial, String operation) {
        initial.requireKeyRecipients(operation);
        return acquire(initial, initial.keyRecipients(), operation);
    }

    static Lease paste(PaneInputCohort.Resolution initial, String operation) {
        initial.requirePasteTarget(operation);
        return acquire(initial, List.of(initial.source()), operation);
    }

    static Lease run(PaneInputCohort.Resolution initial, String operation) {
        initial.requireSingularCommandPane(operation);
        return acquire(initial, initial.keyRecipients(), operation);
    }

    private static Lease acquire(
            PaneInputCohort.Resolution initial, List<PaneInputCohort.Member> members, String operation) {
        Signature signature = Signature.capture(initial, members, operation);
        Set<PaneKey> panes = signature.keys(members);
        DaemonIdentity daemon = DaemonIdentity.capture(initial.authority());
        synchronized (MONITOR) {
            if (panes.stream().anyMatch(HELD::contains)) {
                throw new IllegalStateException(operation + " refuses pane input already owned by another operation");
            }
            HELD.addAll(panes);
        }
        return new Lease(operation, initial.authority(), daemon, signature, panes);
    }

    static final class Lease implements AutoCloseable {

        private final String operation;
        private final PaneInputCohort.Authority authority;
        private final DaemonIdentity daemon;
        private final Signature initial;
        private final Set<PaneKey> panes;
        private boolean closed;

        private Lease(
                String operation,
                PaneInputCohort.Authority authority,
                DaemonIdentity daemon,
                Signature initial,
                Set<PaneKey> panes) {
            this.operation = operation;
            this.authority = authority;
            this.daemon = daemon;
            this.initial = initial;
            this.panes = Set.copyOf(panes);
        }

        List<String> requireSameKeys(PaneInputCohort.Resolution fresh) {
            List<String> resolved = fresh.requireKeyRecipients(operation);
            requireSame(Signature.capture(fresh, fresh.keyRecipients(), operation));
            return resolved;
        }

        void requireSamePaste(PaneInputCohort.Resolution fresh) {
            fresh.requirePasteTarget(operation);
            requireSame(Signature.capture(fresh, List.of(fresh.source()), operation));
        }

        String requireSameRun(PaneInputCohort.Resolution fresh) {
            String command = fresh.requireSingularCommandPane(operation);
            requireSame(Signature.capture(fresh, fresh.keyRecipients(), operation));
            return command;
        }

        PaneInputCohort.Presence presence(Pane pane) {
            PaneInputCohort.Presence observed = PaneInputCohort.presence(pane, authority);
            return observed == PaneInputCohort.Presence.UNKNOWN && daemon.gone()
                    ? PaneInputCohort.Presence.GONE
                    : observed;
        }

        private void requireSame(Signature fresh) {
            synchronized (MONITOR) {
                if (closed || !HELD.containsAll(panes)) {
                    throw new IllegalStateException(operation + " lost pane input ownership");
                }
                if (!initial.equals(fresh)) {
                    throw new IllegalStateException(operation + " refuses pane input state changed after setup");
                }
            }
        }

        @Override
        public void close() {
            synchronized (MONITOR) {
                if (!closed) {
                    HELD.removeAll(panes);
                    closed = true;
                }
            }
        }
    }

    private record Signature(
            Generation generation,
            PaneInputCohort.Member source,
            List<PaneInputCohort.Member> members,
            List<PaneInputCohort.ClientPlacement> terminalClients) {

        private Signature {
            members = List.copyOf(members);
            terminalClients = List.copyOf(terminalClients);
        }

        static Signature capture(
                PaneInputCohort.Resolution resolution, List<PaneInputCohort.Member> members, String operation) {
            return new Signature(
                    Generation.capture(resolution.authority(), operation),
                    resolution.source(),
                    members,
                    resolution.terminalClients());
        }

        Set<PaneKey> keys(List<PaneInputCohort.Member> selected) {
            Set<PaneKey> keys = new HashSet<>();
            for (PaneInputCohort.Member member : selected) {
                keys.add(new PaneKey(generation, member.paneId()));
            }
            return Set.copyOf(keys);
        }
    }

    private record PaneKey(Generation generation, String paneId) {}

    record DaemonIdentity(String realm, long pid, Optional<Instant> started) {

        static DaemonIdentity capture(PaneInputCohort.Authority authority) {
            Optional<Instant> started = authority.realm().equals("local")
                    ? ProcessHandle.of(authority.serverPid())
                            .filter(ProcessHandle::isAlive)
                            .flatMap(process -> process.info().startInstant())
                    : Optional.empty();
            return new DaemonIdentity(authority.realm(), authority.serverPid(), started);
        }

        boolean gone() {
            if (!realm.equals("local")) {
                return false;
            }
            Optional<ProcessHandle> current = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            if (current.isEmpty()) {
                return true;
            }
            return started.flatMap(known ->
                            current.orElseThrow().info().startInstant().map(observed -> !observed.equals(known)))
                    .orElse(false);
        }
    }

    private record Generation(Endpoint endpoint, long serverPid, long startTime) {

        static Generation capture(PaneInputCohort.Authority authority, String operation) {
            return new Generation(
                    Endpoint.capture(authority.realm(), authority.socketPath(), operation),
                    authority.serverPid(),
                    authority.startTime());
        }
    }

    private record Endpoint(String realm, Object fileKey) {

        static Endpoint capture(String realm, String socket, String operation) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(Path.of(socket), BasicFileAttributes.class);
                Object key = attributes.fileKey();
                if (!attributes.isOther() || key == null) {
                    throw new IOException("selected tmux socket had no stable physical identity");
                }
                return new Endpoint(realm, key);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        operation + " could not authenticate the selected tmux socket", failure);
            }
        }
    }
}
