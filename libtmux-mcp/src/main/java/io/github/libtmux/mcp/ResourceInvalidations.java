package io.github.libtmux.mcp;

import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.snapshot.PaneState;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.SessionState;
import io.github.libtmux.snapshot.WindowState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Computes exact resource invalidations without reading tmux or sending notifications. */
final class ResourceInvalidations {

    private ResourceInvalidations() {}

    static Projection project(ServerSnapshot snapshot, Predicate<String> hiddenClient) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(hiddenClient, "hiddenClient");

        Set<SessionId> attached = new LinkedHashSet<>();
        snapshot.clients().stream()
                .filter(client -> !hiddenClient.test(client.name()))
                .flatMap(client -> client.session().stream())
                .forEach(attached::add);

        List<SessionView> sessions = snapshot.sessions().stream()
                .map(session -> session(snapshot, session, attached.contains(session.id())))
                .toList();
        Map<SessionId, SessionView> sessionsById = new LinkedHashMap<>();
        sessions.forEach(session -> sessionsById.putIfAbsent(session.id(), session));

        List<PaneView> panes =
                snapshot.panes().stream().map(pane -> pane(snapshot, pane)).toList();
        Map<PaneId, PaneView> panesById = new LinkedHashMap<>();
        for (PaneView pane : panes) {
            panesById.putIfAbsent(pane.id(), pane);
        }

        return new Projection(
                new Counts(
                        snapshot.sessions().size(),
                        snapshot.windows().size(),
                        snapshot.panes().size()),
                sessions,
                sessionsById,
                panes,
                panesById);
    }

    static Set<String> between(Projection before, Projection after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Set<String> changed = new LinkedHashSet<>();

        if (!before.counts.equals(after.counts)) {
            changed.add(Resources.SERVER_URI);
        }
        if (!before.sessions.equals(after.sessions)) {
            changed.add(Resources.SESSIONS_URI);
        }
        for (SessionId id : union(before.sessionsById.keySet(), after.sessionsById.keySet())) {
            SessionView oldSession = before.sessionsById.get(id);
            SessionView newSession = after.sessionsById.get(id);
            if (!Objects.equals(oldSession, newSession)) {
                if (oldSession != null) {
                    changed.add(Resources.sessionUri(oldSession.name()));
                }
                if (newSession != null) {
                    changed.add(Resources.sessionUri(newSession.name()));
                }
            }
        }

        if (!before.panes.equals(after.panes)) {
            changed.add(Resources.PANES_URI);
        }
        for (PaneId id : union(before.panesById.keySet(), after.panesById.keySet())) {
            PaneView oldPane = before.panesById.get(id);
            PaneView newPane = after.panesById.get(id);
            if (!Objects.equals(oldPane, newPane)) {
                changed.add(Resources.paneUri(id));
            }
            if (oldPane == null
                    || newPane == null
                    || !oldPane.size().equals(newPane.size())
                    || oldPane.pid() != newPane.pid()) {
                changed.add(Resources.paneContentUri(id));
            }
        }
        return immutable(changed);
    }

    static Set<String> allKnown(Projection... projections) {
        Set<String> changed = new LinkedHashSet<>();
        changed.add(Resources.SERVER_URI);
        changed.add(Resources.SESSIONS_URI);
        changed.add(Resources.PANES_URI);
        for (Projection projection : projections) {
            Objects.requireNonNull(projection, "projection");
            projection.sessionsById.values().stream()
                    .map(SessionView::name)
                    .map(Resources::sessionUri)
                    .forEach(changed::add);
            for (PaneId pane : projection.panesById.keySet()) {
                changed.add(Resources.paneUri(pane));
                changed.add(Resources.paneContentUri(pane));
            }
        }
        return immutable(changed);
    }

    static Set<String> output(PaneId pane) {
        return Set.of(Resources.paneContentUri(Objects.requireNonNull(pane, "pane")));
    }

    static Set<String> droppedOutput(Projection projection) {
        Objects.requireNonNull(projection, "projection");
        Set<String> changed = new LinkedHashSet<>();
        projection.panesById.keySet().stream().map(Resources::paneContentUri).forEach(changed::add);
        return immutable(changed);
    }

    private static SessionView session(ServerSnapshot snapshot, SessionState session, boolean attached) {
        List<String> windows =
                snapshot.windowsOf(session.id()).stream().map(WindowState::name).toList();
        return new SessionView(session.id(), session.name(), attached, windows.size(), windows);
    }

    private static PaneView pane(ServerSnapshot snapshot, PaneState pane) {
        String session = snapshot.session(pane.context().session())
                .orElseThrow(() -> new IllegalArgumentException("pane refers to an absent session"))
                .name();
        String window = snapshot.window(pane.context())
                .orElseThrow(() -> new IllegalArgumentException("pane refers to an absent window"))
                .name();
        return new PaneView(
                pane.id(),
                session,
                window,
                pane.context().window().value(),
                pane.currentCommand(),
                pane.currentPath().toString(),
                pane.size().toString(),
                pane.pid(),
                pane.active());
    }

    private static <T> Set<T> union(Set<T> before, Set<T> after) {
        Set<T> union = new LinkedHashSet<>(before);
        union.addAll(after);
        return union;
    }

    private static Set<String> immutable(Set<String> values) {
        return values.isEmpty() ? Set.of() : Collections.unmodifiableSet(values);
    }

    static final class Projection {

        private final Counts counts;
        private final List<SessionView> sessions;
        private final Map<SessionId, SessionView> sessionsById;
        private final List<PaneView> panes;
        private final Map<PaneId, PaneView> panesById;

        private Projection(
                Counts counts,
                List<SessionView> sessions,
                Map<SessionId, SessionView> sessionsById,
                List<PaneView> panes,
                Map<PaneId, PaneView> panesById) {
            this.counts = counts;
            this.sessions = List.copyOf(sessions);
            this.sessionsById = unmodifiableMap(sessionsById);
            this.panes = List.copyOf(panes);
            this.panesById = unmodifiableMap(panesById);
        }
    }

    private static <K, V> Map<K, V> unmodifiableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private record Counts(int sessions, int windows, int panes) {}

    private record SessionView(SessionId id, String name, boolean attached, int windows, List<String> windowNames) {

        private SessionView {
            windowNames = List.copyOf(windowNames);
        }
    }

    private record PaneView(
            PaneId id,
            String session,
            String window,
            String windowId,
            String command,
            String path,
            String size,
            long pid,
            boolean active) {}
}
