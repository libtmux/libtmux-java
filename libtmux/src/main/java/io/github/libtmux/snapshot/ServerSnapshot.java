package io.github.libtmux.snapshot;

import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.WindowId;
import io.github.libtmux.WindowIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * One tmux hierarchy, as it was at one moment.
 *
 * <p>Every question below is answered from what was captured, so traversing a snapshot issues no
 * commands and cannot observe a half-changed server. It claims a moment rather than current truth:
 * tmux offers no transaction across separate listings, and pretending otherwise would be a promise
 * this cannot keep.
 */
public final class ServerSnapshot {

    private final Instant capturedAt;
    private final OptionalLong serverPid;
    private final Optional<TmuxVersion> serverVersion;
    private final List<SessionState> sessions;
    private final List<WindowState> windows;
    private final List<PaneState> panes;
    private final List<ClientState> clients;

    private final Map<SessionId, SessionState> sessionsById;
    private final Map<SessionId, List<WindowState>> windowsBySession;
    private final Map<WindowContext, WindowState> windowsByContext;
    private final Map<WindowContext, List<PaneState>> panesByContext;

    private ServerSnapshot(
            Instant capturedAt,
            OptionalLong serverPid,
            Optional<TmuxVersion> serverVersion,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        this.capturedAt = capturedAt;
        this.serverPid = serverPid;
        this.serverVersion = serverVersion;
        this.sessions = sessions;
        this.windows = windows;
        this.panes = panes;
        this.clients = clients;

        this.sessionsById = unique(sessions, SessionState::id, "session");
        this.windowsBySession = group(windows, window -> window.context().session());
        this.windowsByContext = unique(windows, WindowState::context, "window context");
        unique(
                windows,
                window -> new WindowSlot(
                        window.context().session(), window.context().index()),
                "window slot");
        this.panesByContext = group(panes, PaneState::context);
        unique(panes, pane -> new PaneKey(pane.context(), pane.id()), "pane context");
        unique(panes, pane -> new PaneSlot(pane.context(), pane.index()), "pane slot");
        requirePaneOwnership(panes);
    }

    /**
     * Assembles a capture, checking that the listings agree with each other.
     *
     * @throws IllegalArgumentException if the listings contain duplicate identities or disagree on
     *     the captured hierarchy
     */
    public static ServerSnapshot of(
            Instant capturedAt,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        return of(capturedAt, OptionalLong.empty(), Optional.empty(), sessions, windows, panes, clients);
    }

    /** Assembles a capture tied to the live tmux process that produced it. */
    public static ServerSnapshot of(
            Instant capturedAt,
            long serverPid,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        if (serverPid < 1) {
            throw new IllegalArgumentException("serverPid is not positive: " + serverPid);
        }
        return of(capturedAt, OptionalLong.of(serverPid), Optional.empty(), sessions, windows, panes, clients);
    }

    /** Assembles a capture tied to the live tmux process and version that produced it. */
    public static ServerSnapshot of(
            Instant capturedAt,
            long serverPid,
            TmuxVersion serverVersion,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        if (serverPid < 1) {
            throw new IllegalArgumentException("serverPid is not positive: " + serverPid);
        }
        return of(
                capturedAt,
                OptionalLong.of(serverPid),
                Optional.of(Objects.requireNonNull(serverVersion, "serverVersion")),
                sessions,
                windows,
                panes,
                clients);
    }

    private static ServerSnapshot of(
            Instant capturedAt,
            OptionalLong serverPid,
            Optional<TmuxVersion> serverVersion,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        Objects.requireNonNull(capturedAt, "capturedAt");
        ServerSnapshot snapshot = new ServerSnapshot(
                capturedAt,
                serverPid,
                serverVersion,
                List.copyOf(sessions),
                List.copyOf(windows),
                List.copyOf(panes),
                List.copyOf(clients));
        snapshot.requireClosed();
        return snapshot;
    }

    /**
     * Rejects a capture whose listings do not describe one hierarchy.
     *
     * <p>Each refusal names what was orphaned and what it wanted, because this fires on a race
     * between four listings and the ids are the only way to tell which listing was the stale one.
     */
    private void requireClosed() {
        for (PaneState pane : panes) {
            if (!windowsByContext.containsKey(pane.context())) {
                throw new IllegalArgumentException("pane %s was captured under window %s, which no listing saw"
                        .formatted(pane.id(), pane.context().window()));
            }
        }
        for (WindowState window : windows) {
            if (!sessionsById.containsKey(window.context().session())) {
                throw new IllegalArgumentException("window %s was captured under session %s, which no listing saw"
                        .formatted(window.context().window(), window.context().session()));
            }
            int capturedPanes =
                    panesByContext.getOrDefault(window.context(), List.of()).size();
            if (window.panes() != capturedPanes) {
                throw new IllegalArgumentException("window %s reported %d panes but the listing captured %d"
                        .formatted(window.context().window(), window.panes(), capturedPanes));
            }
        }
        for (SessionState session : sessions) {
            int capturedWindows =
                    windowsBySession.getOrDefault(session.id(), List.of()).size();
            if (session.windows() != capturedWindows) {
                throw new IllegalArgumentException("session %s reported %d windows but the listing captured %d"
                        .formatted(session.id(), session.windows(), capturedWindows));
            }
        }
        for (ClientState client : clients) {
            client.session().ifPresent(session -> {
                if (!sessionsById.containsKey(session)) {
                    throw new IllegalArgumentException("client %s was captured under session %s, which no listing saw"
                            .formatted(client.name(), session));
                }
            });
        }
    }

    /** When this capture was taken. */
    public Instant capturedAt() {
        return capturedAt;
    }

    /** The tmux process that produced this capture, absent when assembled from detached state. */
    public OptionalLong serverPid() {
        return serverPid;
    }

    /** The tmux version that produced this capture, absent when assembled from detached state. */
    public Optional<TmuxVersion> serverVersion() {
        return serverVersion;
    }

    /** Every session, in tmux's order. */
    public List<SessionState> sessions() {
        return sessions;
    }

    /** Every winlink, in tmux's order, including a window linked into more than one session twice. */
    public List<WindowState> windows() {
        return windows;
    }

    /** Every pane, in tmux's order. */
    public List<PaneState> panes() {
        return panes;
    }

    /** Every attached client. */
    public List<ClientState> clients() {
        return clients;
    }

    /** The session with this id, if the capture saw it. */
    public Optional<SessionState> session(SessionId id) {
        return Optional.ofNullable(sessionsById.get(id));
    }

    /** The first session with this name, if the capture saw one. */
    public Optional<SessionState> session(String name) {
        return sessions.stream().filter(session -> session.name().equals(name)).findFirst();
    }

    /** The winlink at this exact position, if the capture saw it. */
    public Optional<WindowState> window(WindowContext context) {
        return Optional.ofNullable(windowsByContext.get(context));
    }

    /** The winlinks in one session, in order. Empty when the capture never saw that session. */
    public List<WindowState> windowsOf(SessionId session) {
        return windowsBySession.getOrDefault(session, List.of());
    }

    /** The panes under one winlink, in order. Empty when the capture never saw that winlink. */
    public List<PaneState> panesOf(WindowContext context) {
        return panesByContext.getOrDefault(context, List.of());
    }

    @Override
    public String toString() {
        return "ServerSnapshot[capturedAt=" + capturedAt + ", serverPid=" + serverPid + ", sessions="
                + sessions.size() + ", windows=" + windows.size() + ", panes=" + panes.size() + ", clients="
                + clients.size() + "]";
    }

    private static <K, V> Map<K, List<V>> group(List<V> values, Function<V, K> key) {
        Map<K, List<V>> grouped = new LinkedHashMap<>();
        for (V value : values) {
            grouped.computeIfAbsent(key.apply(value), unused -> new ArrayList<>())
                    .add(value);
        }
        grouped.replaceAll((unused, group) -> Collections.unmodifiableList(group));
        return Collections.unmodifiableMap(grouped);
    }

    private static <K, V> Map<K, V> unique(List<V> values, Function<V, K> key, String kind) {
        Map<K, V> indexed = new LinkedHashMap<>();
        for (V value : values) {
            K identity = key.apply(value);
            if (indexed.putIfAbsent(identity, value) != null) {
                throw new IllegalArgumentException("duplicate " + kind + " '" + identity + "'");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static void requirePaneOwnership(List<PaneState> panes) {
        Map<PaneId, WindowId> windows = new LinkedHashMap<>();
        for (PaneState pane : panes) {
            WindowId window = pane.context().window();
            WindowId previous = windows.putIfAbsent(pane.id(), window);
            if (previous != null && !previous.equals(window)) {
                throw new IllegalArgumentException(
                        "pane %s was captured under both window %s and %s".formatted(pane.id(), previous, window));
            }
        }
    }

    private record PaneKey(WindowContext context, PaneId id) {}

    private record PaneSlot(WindowContext context, int index) {}

    private record WindowSlot(SessionId session, WindowIndex index) {}
}
