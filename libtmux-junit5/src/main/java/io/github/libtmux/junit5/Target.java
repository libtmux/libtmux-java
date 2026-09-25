package io.github.libtmux.junit5;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A session, one of its windows, and one of that window's panes: what a command is scoped to. */
record Target(FakeSession session, FakeWindow window, FakePane pane) {

    static Optional<Target> active(List<FakeSession> sessions) {
        return sessions.stream().findFirst().map(session -> {
            FakeWindow window =
                    session.windows.stream().filter(w -> w.active).findFirst().orElse(session.windows.getFirst());
            return new Target(session, window, window.active());
        });
    }

    /** Resolves {@code $id}, {@code @id}, {@code %id} and {@code =name}, with any trailing separator. */
    static Optional<Target> resolve(List<FakeSession> sessions, @Nullable String spec) {
        if (spec == null) {
            return Optional.empty();
        }
        String wanted = spec.replaceAll("[:.]+$", "");
        for (FakeSession session : sessions) {
            if (wanted.equals(session.id) || wanted.equals("=" + session.name)) {
                FakeWindow window = session.windows.stream()
                        .filter(w -> w.active)
                        .findFirst()
                        .orElse(session.windows.getFirst());
                return Optional.of(new Target(session, window, window.active()));
            }
            for (FakeWindow window : session.windows) {
                if (wanted.equals(window.id)) {
                    return Optional.of(new Target(session, window, window.active()));
                }
                for (FakePane pane : window.panes) {
                    if (wanted.equals(pane.id)) {
                        return Optional.of(new Target(session, window, pane));
                    }
                }
            }
        }
        return Optional.empty();
    }
}
