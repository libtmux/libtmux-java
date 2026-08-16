package io.github.libtmux.control;

import java.util.List;
import java.util.Optional;

/**
 * Something tmux volunteered, rather than an answer to a request.
 *
 * <p>A control client is told about changes as they happen — a window appearing, a session being
 * renamed, a layout moving — without anything asking. That is the difference between watching a
 * server and polling one.
 *
 * <p>The kind is tmux's own notification name with the leading {@code %} removed, and the fields are
 * what followed it. Deliberately not modelled one record per kind: tmux adds notifications between
 * releases, and a closed set here would drop the ones a newer tmux sends.
 *
 * @param kind the notification name, such as {@code window-add} or {@code subscription-changed}
 * @param fields the words that followed it, before any {@code :} separator
 * @param value what followed a {@code :} separator, which only a subscription carries
 */
public record ControlEvent(String kind, List<String> fields, Optional<String> value) {

    public ControlEvent {
        fields = List.copyOf(fields);
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
        int separator = body.indexOf(" : ");
        String head = separator < 0 ? body : body.substring(0, separator);
        // A subscription's value is whatever the format expanded to, so it is taken whole rather than
        // split: it may contain spaces, and often does.
        Optional<String> value = separator < 0 ? Optional.empty() : Optional.of(body.substring(separator + 3));
        String[] words = head.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ControlEvent(words[0], List.of(words).subList(1, words.length), value));
    }

    /** The name a subscription was registered under, for an event that came from one. */
    public Optional<String> subscription() {
        return "subscription-changed".equals(kind) && !fields.isEmpty() ? Optional.of(fields.get(0)) : Optional.empty();
    }

    /** The first field naming a pane, which is how an event says which pane it is about. */
    public Optional<String> paneId() {
        return fields.stream().filter(field -> field.startsWith("%")).findFirst();
    }

    /** The first field naming a window. */
    public Optional<String> windowId() {
        return fields.stream().filter(field -> field.startsWith("@")).findFirst();
    }
}
