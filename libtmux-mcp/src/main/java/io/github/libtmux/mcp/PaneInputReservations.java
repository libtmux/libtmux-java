package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.PaneInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Process-wide ownership of the panes an MCP input operation may reach. */
final class PaneInputReservations {

    private static final Object MONITOR = new Object();

    /** Each owned pane and the operation owning it, so an interrupt can tell whose it is. */
    private static final Map<PaneKey, String> HELD = new HashMap<>();

    /** The operation whose retained ownership an interrupt is allowed to reach through. */
    private static final String RUN = "run_shell_command";

    private PaneInputReservations() {}

    static Lease keys(Pane pane, PaneInputCohort.Resolution initial, String operation) {
        initial.requireKeyRecipients(operation);
        return acquire(pane, initial, initial.keyRecipients(), operation, false);
    }

    /**
     * A lease for keys that can only stop what is running, rather than type anything.
     *
     * <p>A run that passed its deadline keeps the pane while its command is still going, and tells
     * the caller to interrupt it. That advice has to be reachable, so an interrupt passes through a
     * run's ownership instead of being refused by it — otherwise the one server that can start a
     * command that hangs is the one that cannot stop it. It owns nothing itself: the run still owns
     * the pane, until its command reports and releases it.
     *
     * <p>It reaches through a run and nothing else. A pane no run holds is taken the ordinary way,
     * owned for as long as the keys take and released after, so an interrupt never gives up the
     * exclusion the other operations rely on; only the panes a run is holding are passed through,
     * and those the run goes on owning.
     */
    static Lease interrupting(Pane pane, PaneInputCohort.Resolution initial, String operation) {
        initial.requireKeyRecipients(operation);
        return acquire(pane, initial, initial.keyRecipients(), operation, true);
    }

    static Lease paste(Pane pane, PaneInputCohort.Resolution initial, String operation) {
        initial.requirePasteTarget(operation);
        return acquire(pane, initial, List.of(initial.source()), operation, false);
    }

    static Lease run(Pane pane, PaneInputCohort.Resolution initial, String operation) {
        initial.requireSingularCommandPane(operation);
        return acquire(pane, initial, initial.keyRecipients(), operation, false);
    }

    private static Lease acquire(
            Pane pane,
            PaneInputCohort.Resolution initial,
            List<PaneInputCohort.Member> members,
            String operation,
            boolean interrupting) {
        Signature signature = Signature.capture(initial, members, operation);
        Set<PaneKey> panes = signature.keys(members);
        DaemonIdentity daemon = DaemonIdentity.capture(initial.authority());
        Set<PaneKey> owned = new HashSet<>();
        boolean takeSource;
        synchronized (MONITOR) {
            for (PaneKey key : panes) {
                String holder = HELD.get(key);
                if (holder != null && !(interrupting && holder.equals(RUN))) {
                    throw new IllegalStateException(
                            operation + " refuses pane input already owned by another operation");
                }
                if (holder == null) {
                    owned.add(key);
                }
            }
            owned.forEach(key -> HELD.put(key, operation));
            takeSource =
                    owned.stream().anyMatch(key -> key.paneId().equals(pane.id().value()));
        }
        List<PaneInput.Lease> input = new ArrayList<>();
        if (takeSource) {
            try {
                input.add(operation.equals(RUN) ? PaneInput.holdInterruptible(pane) : PaneInput.hold(pane));
            } catch (RuntimeException failure) {
                synchronized (MONITOR) {
                    HELD.keySet().removeAll(owned);
                }
                throw failure;
            }
        }
        return new Lease(operation, initial.authority(), daemon, signature, owned, input, interrupting && !takeSource);
    }

    static final class Lease implements AutoCloseable {

        private final String operation;
        private final PaneInputCohort.Authority authority;
        private final DaemonIdentity daemon;
        private final Signature initial;
        /** The panes this lease put in {@link #HELD}: all of them, or the ones no run was holding. */
        private final Set<PaneKey> owned;

        private final List<PaneInput.Lease> input;
        private final boolean interruptPass;
        private boolean closed;

        private Lease(
                String operation,
                PaneInputCohort.Authority authority,
                DaemonIdentity daemon,
                Signature initial,
                Set<PaneKey> owned,
                List<PaneInput.Lease> input,
                boolean interruptPass) {
            this.operation = operation;
            this.authority = authority;
            this.daemon = daemon;
            this.initial = initial;
            this.owned = Set.copyOf(owned);
            this.input = List.copyOf(input);
            this.interruptPass = interruptPass;
        }

        /** True when a run already holds this pane and this lease may only stop it. */
        boolean passesThrough() {
            return interruptPass;
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
                if (closed || !HELD.keySet().containsAll(owned)) {
                    throw new IllegalStateException(operation + " lost pane input ownership");
                }
                if (!initial.equals(fresh)) {
                    throw new IllegalStateException(operation + " refuses pane input state changed after setup");
                }
            }
        }

        @Override
        public void close() {
            List<PaneInput.Lease> releasing;
            synchronized (MONITOR) {
                if (closed) {
                    return;
                }
                HELD.keySet().removeAll(owned);
                closed = true;
                releasing = input;
            }
            releasing.forEach(PaneInput.Lease::close);
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
