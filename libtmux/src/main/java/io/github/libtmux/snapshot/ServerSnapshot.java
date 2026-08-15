package io.github.libtmux.snapshot;

import io.github.libtmux.SessionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final List<SessionState> sessions;
    private final List<WindowState> windows;
    private final List<PaneState> panes;
    private final List<ClientState> clients;

    private final Map<SessionId, SessionState> sessionsById;
    private final Map<SessionId, List<WindowState>> windowsBySession;
    private final Map<WindowContext, List<PaneState>> panesByContext;

    private ServerSnapshot(
            Instant capturedAt,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        this.capturedAt = capturedAt;
        this.sessions = sessions;
        this.windows = windows;
        this.panes = panes;
        this.clients = clients;

        Map<SessionId, SessionState> byId = new LinkedHashMap<>();
        for (SessionState session : sessions) {
            byId.put(session.id(), session);
        }
        this.sessionsById = Collections.unmodifiableMap(byId);
        this.windowsBySession = group(windows, window -> window.context().session());
        this.panesByContext = group(panes, PaneState::context);
    }

    /**
     * Assembles a capture, checking that the listings agree with each other.
     *
     * @throws IllegalArgumentException if a pane was reported under a window the capture never saw,
     *     which means the listings were taken across a change and do not describe one hierarchy
     */
    public static ServerSnapshot of(
            Instant capturedAt,
            List<SessionState> sessions,
            List<WindowState> windows,
            List<PaneState> panes,
            List<ClientState> clients) {
        Objects.requireNonNull(capturedAt, "capturedAt");
        ServerSnapshot snapshot = new ServerSnapshot(
                capturedAt, List.copyOf(sessions), List.copyOf(windows), List.copyOf(panes), List.copyOf(clients));
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
            if (!panesByContext.containsKey(pane.context())
                    || !windowsBySession.containsKey(pane.context().session())) {
                throw new IllegalArgumentException("pane %s was captured under window %s, which no listing saw"
                        .formatted(pane.id(), pane.context().window()));
            }
        }
        for (WindowState window : windows) {
            if (!sessionsById.containsKey(window.context().session())) {
                throw new IllegalArgumentException("window %s was captured under session %s, which no listing saw"
                        .formatted(window.context().window(), window.context().session()));
            }
        }
    }

    /** When this capture was taken. */
    public Instant capturedAt() {
        return capturedAt;
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
        return windows.stream()
                .filter(window -> window.context().equals(context))
                .findFirst();
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
        return "ServerSnapshot[capturedAt=" + capturedAt + ", sessions=" + sessions.size() + ", windows="
                + windows.size() + ", panes=" + panes.size() + ", clients=" + clients.size() + "]";
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
}
