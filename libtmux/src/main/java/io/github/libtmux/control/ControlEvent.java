package io.github.libtmux.control;

import io.github.libtmux.PaneId;
import io.github.libtmux.WindowId;
import java.util.List;
import java.util.Optional;
import kotlin.annotations.jvm.ReadOnly;

/**
 * Something tmux volunteered, rather than an answer to a request.
 *
 * <p>A control client is told about changes as they happen — a window appearing, a session being
 * renamed, a layout moving — without anything asking. That is the difference between watching a
 * server and polling one.
 *
 * <p>Two readings of one line. {@link #notification()} is typed, for matching on: a sealed set of
 * what tmux sends, with a {@link Notification.Unknown} case so that a notification a newer tmux adds
 * still arrives rather than being dropped. {@link #kind()}, {@link #fields()} and {@link #value()}
 * are what tmux wrote, word by word, for anything the typed reading does not cover.
 *
 * @param kind the notification name, such as {@code window-add} or {@code subscription-changed}
 * @param fields the words that followed it, before any {@code :} separator
 * @param value what followed a {@code :} separator, which only a subscription carries
 * @param notification the same notification, typed
 */
public record ControlEvent(
        String kind, @ReadOnly List<String> fields, Optional<String> value, Notification notification) {

    public ControlEvent {
        fields = List.copyOf(fields);
    }

    /**
     * An event from its words, with the typed reading taken from them.
     *
     * <p>A name is rejoined from the words with single spaces, so one tmux wrote with a run of them
     * reads back with one. {@link #parse} reads the line tmux wrote and has no such loss.
     */
    public ControlEvent(String kind, List<String> fields, Optional<String> value) {
        this(kind, fields, value, Notification.read(kind, String.join(" ", fields), value));
    }

    /**
     * Reads one notification line.
     *
     * @param line the line as tmux wrote it, leading {@code %} and all
     * @return the event, or empty when the line is not a notification
     */
    static Optional<ControlEvent> parse(String line) {
        if (!line.startsWith("%") || line.length() < 2) {
            return Optional.empty();
        }
        String body = line.substring(1);
        // Only a subscription separates a value with " : ". Anywhere else it is part of what tmux
        // wrote: a window, session, or buffer name may hold one, as may a message.
        int separator = body.startsWith("subscription-changed ") ? body.indexOf(" : ") : -1;
        String head = separator < 0 ? body : body.substring(0, separator);
        // A subscription's value is whatever the format expanded to, so it is taken whole rather than
        // split: it may contain spaces, and often does.
        Optional<String> value = separator < 0 ? Optional.empty() : Optional.of(body.substring(separator + 3));
        String[] words = head.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return Optional.empty();
        }
        String kind = words[0];
        // The text after the name exactly as tmux wrote it, so a name holding a run of spaces is read
        // back as that name.
        String rest = head.length() > kind.length() ? head.substring(kind.length() + 1) : "";
        return Optional.of(new ControlEvent(
                kind, List.of(words).subList(1, words.length), value, Notification.read(kind, rest, value)));
    }

    /** The name a subscription was registered under, for an event that came from one. */
    public Optional<String> subscription() {
        return "subscription-changed".equals(kind) && !fields.isEmpty() ? Optional.of(fields.get(0)) : Optional.empty();
    }

    /** The first field naming a pane, which is how an event says which pane it is about. */
    public Optional<PaneId> paneId() {
        return fields.stream()
                .filter(field -> field.startsWith("%") && field.length() > 1)
                .findFirst()
                .map(PaneId::new);
    }

    /** The first field naming a window. */
    public Optional<WindowId> windowId() {
        return fields.stream()
                .filter(field -> field.startsWith("@") && field.length() > 1)
                .findFirst()
                .map(WindowId::new);
    }
}
