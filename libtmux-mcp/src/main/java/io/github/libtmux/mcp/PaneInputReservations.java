package io.github.libtmux.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
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

    private static Lease acquire(
            PaneInputCohort.Resolution initial, List<PaneInputCohort.Member> members, String operation) {
        Signature signature = Signature.capture(initial, members, operation);
        Set<PaneKey> panes = signature.keys(members);
        synchronized (MONITOR) {
            if (panes.stream().anyMatch(HELD::contains)) {
                throw new IllegalStateException(operation + " refuses pane input already owned by another operation");
            }
            HELD.addAll(panes);
        }
        return new Lease(operation, signature, panes);
    }

    static final class Lease implements AutoCloseable {

        private final String operation;
        private final Signature initial;
        private final Set<PaneKey> panes;
        private boolean closed;

        private Lease(String operation, Signature initial, Set<PaneKey> panes) {
            this.operation = operation;
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
            Set<String> attendedPaneIds) {

        private Signature {
            members = List.copyOf(members);
            attendedPaneIds = Set.copyOf(attendedPaneIds);
        }

        static Signature capture(
                PaneInputCohort.Resolution resolution, List<PaneInputCohort.Member> members, String operation) {
            return new Signature(
                    Generation.capture(resolution.authority(), operation),
                    resolution.source(),
                    members,
                    resolution.attendedPaneIds());
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
