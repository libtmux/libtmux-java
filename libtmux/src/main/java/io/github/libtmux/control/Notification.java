package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import io.github.libtmux.SessionId;
import io.github.libtmux.WindowId;
import io.github.libtmux.WindowLayout;
import java.util.Optional;

/**
 * What a control-mode notification says, typed, for matching on.
 *
 * <pre>{@code
 * switch (event.notification()) {
 *     case Notification.WindowRenamed(WindowId window, String name, boolean attached) -> relabel(window, name);
 *     case Notification.SessionsChanged changed -> refreshSessions();
 *     default -> {}
 * }
 * }</pre>
 *
 * <p>Sealed, with {@link Unknown} as the case for everything else. tmux adds notifications between
 * releases, and a closed set with nowhere to put a new one would drop it; here a notification this
 * library does not model yet still arrives, as {@code Unknown}, and {@link ControlEvent#kind()} and
 * {@link ControlEvent#fields()} still carry what tmux wrote.
 *
 * <p>The forms are tmux's own, from {@code control-notify.c}. A name — of a window, a session, a
 * client — is everything after the id, spaces included, as tmux writes it.
 */
public sealed interface Notification {

    /** {@code %window-add}: a window was created; {@code attached} is false for {@code unlinked-window-add}. */
    record WindowAdded(WindowId window, boolean attached) implements Notification {}

    /** {@code %window-close}: a window was destroyed; {@code attached} as for {@link WindowAdded}. */
    record WindowClosed(WindowId window, boolean attached) implements Notification {}

    /** {@code %window-renamed}: a window has a new name; {@code attached} as for {@link WindowAdded}. */
    record WindowRenamed(WindowId window, String name, boolean attached) implements Notification {}

    /** {@code %window-pane-changed}: a window's active pane changed. */
    record WindowPaneChanged(WindowId window, PaneId pane) implements Notification {}

    /** {@code %layout-change}: a window's layout changed, in whichever form tmux reports it. */
    record LayoutChanged(WindowId window, WindowLayout layout) implements Notification {}

    /** {@code %session-changed}: the attached client is now attached to this session. */
    record SessionChanged(SessionId session, String name) implements Notification {}

    /** {@code %session-renamed}: a session has a new name. */
    record SessionRenamed(SessionId session, String name) implements Notification {}

    /** {@code %sessions-changed}: a session was created or destroyed. */
    record SessionsChanged() implements Notification {}

    /** {@code %session-window-changed}: a session's current window changed. */
    record SessionWindowChanged(SessionId session, WindowId window) implements Notification {}

    /** {@code %client-session-changed}: another client is now attached to this session. */
    record ClientSessionChanged(String client, SessionId session, String name) implements Notification {}

    /** {@code %client-detached}: a client detached. */
    record ClientDetached(String client) implements Notification {}

    /** {@code %pane-mode-changed}: a pane entered or left a mode. */
    record PaneModeChanged(PaneId pane) implements Notification {}

    /** {@code %paste-buffer-changed}: a paste buffer was created or changed. */
    record PasteBufferChanged(String buffer) implements Notification {}

    /** {@code %paste-buffer-deleted}: a paste buffer was deleted. */
    record PasteBufferDeleted(String buffer) implements Notification {}

    /**
     * {@code %subscription-changed}: a watched format has a new value.
     *
     * @param name what the subscription was registered under
     * @param value what the format expanded to
     * @param session the session it is about, when tmux named one
     * @param window the window, when tmux named one
     * @param pane the pane, when tmux named one
     */
    record SubscriptionChanged(
            String name, String value, Optional<SessionId> session, Optional<WindowId> window, Optional<PaneId> pane)
            implements Notification {}

    /** {@code %exit}: the client is exiting, with tmux's reason when it gave one. */
    record Exit(Optional<String> reason) implements Notification {}

    /** A notification this library does not model; {@link ControlEvent} still carries all of it. */
    record Unknown(String kind) implements Notification {}

    /**
     * Reads one notification from its kind and the text after it.
     *
     * @param kind the name without its {@code %}
     * @param rest what followed the name, as tmux wrote it, without any {@code " : "} value
     * @param value what followed {@code " : "}, which only a subscription carries
     */
    static Notification read(String kind, String rest, Optional<String> value) {
        String[] words = rest.isEmpty() ? new String[0] : rest.split(" ", -1);
        String first = words.length > 0 ? words[0] : "";
        String afterFirst = rest.indexOf(' ') < 0 ? "" : rest.substring(rest.indexOf(' ') + 1);
        String second = words.length > 1 ? words[1] : "";
        String afterSecond = afterFirst.indexOf(' ') < 0 ? "" : afterFirst.substring(afterFirst.indexOf(' ') + 1);
        Notification read =
                switch (kind) {
                    case "window-add", "unlinked-window-add" ->
                        window(first)
                                .map(w -> (Notification) new WindowAdded(w, kind.equals("window-add")))
                                .orElse(null);
                    case "window-close", "unlinked-window-close" ->
                        window(first)
                                .map(w -> (Notification) new WindowClosed(w, kind.equals("window-close")))
                                .orElse(null);
                    case "window-renamed", "unlinked-window-renamed" ->
                        window(first)
                                .map(w ->
                                        (Notification) new WindowRenamed(w, afterFirst, kind.equals("window-renamed")))
                                .orElse(null);
                    case "window-pane-changed" ->
                        window(first)
                                .flatMap(w -> pane(second).map(p -> (Notification) new WindowPaneChanged(w, p)))
                                .orElse(null);
                    case "layout-change" ->
                        window(first)
                                .map(w -> (Notification) new LayoutChanged(w, WindowLayout.of(second)))
                                .orElse(null);
                    case "session-changed" ->
                        session(first)
                                .map(s -> (Notification) new SessionChanged(s, afterFirst))
                                .orElse(null);
                    case "session-renamed" ->
                        session(first)
                                .map(s -> (Notification) new SessionRenamed(s, afterFirst))
                                .orElse(null);
                    case "sessions-changed" -> new SessionsChanged();
                    case "session-window-changed" ->
                        session(first)
                                .flatMap(s -> window(second).map(w -> (Notification) new SessionWindowChanged(s, w)))
                                .orElse(null);
                    case "client-session-changed" ->
                        session(second)
                                .map(s -> (Notification) new ClientSessionChanged(first, s, afterSecond))
                                .orElse(null);
                    case "client-detached" -> first.isEmpty() ? null : new ClientDetached(rest);
                    case "pane-mode-changed" ->
                        pane(first)
                                .map(p -> (Notification) new PaneModeChanged(p))
                                .orElse(null);
                    case "paste-buffer-changed" -> first.isEmpty() ? null : new PasteBufferChanged(rest);
                    case "paste-buffer-deleted" -> first.isEmpty() ? null : new PasteBufferDeleted(rest);
                    case "subscription-changed" ->
                        first.isEmpty()
                                ? null
                                : new SubscriptionChanged(
                                        first,
                                        value.orElse(""),
                                        words.length > 1 ? session(words[1]) : Optional.empty(),
                                        words.length > 2 ? window(words[2]) : Optional.empty(),
                                        words.length > 4 ? pane(words[4]) : Optional.empty());
                    case "exit" -> new Exit(rest.isEmpty() ? Optional.empty() : Optional.of(rest));
                    default -> null;
                };
        return read == null ? new Unknown(kind) : read;
    }

    private static Optional<WindowId> window(String word) {
        return word.startsWith("@") && word.length() > 1 ? optional(() -> new WindowId(word)) : Optional.empty();
    }

    private static Optional<SessionId> session(String word) {
        return word.startsWith("$") && word.length() > 1 ? optional(() -> new SessionId(word)) : Optional.empty();
    }

    private static Optional<PaneId> pane(String word) {
        return word.startsWith("%") && word.length() > 1 ? optional(() -> new PaneId(word)) : Optional.empty();
    }

    /** An id tmux wrote in a form the library refuses is not an id this can report. */
    private static <T> Optional<T> optional(java.util.function.Supplier<T> id) {
        try {
            return Optional.of(id.get());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
