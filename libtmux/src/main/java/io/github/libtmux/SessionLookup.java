package io.github.libtmux;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandResult;
import java.util.List;
import java.util.Optional;

/** Resolves a caller-given session name to the id tmux keeps it under. */
final class SessionLookup {

    private SessionLookup() {}

    /**
     * The id of the session tmux keeps under this name, read from one listing.
     *
     * <p>Not a {@code -t =name} target: tmux reads {@code .} and {@code :} in a target as window and
     * pane separators, and it may have stored the name differently from how it was given.
     */
    static Optional<SessionId> named(Server server, String name) {
        RowFormat format = RowFormat.of("session_id", "session_name", "version");
        CommandResult result = server.cmd("list-sessions", "-F", format.template());
        if (!result.succeeded()) {
            // A live server with no sessions lists nothing and exits 0, on 3.2a and 3.7d alike, so
            // any failure is not an answer: no daemon, a socket this user cannot open, or a binary
            // that is not tmux. failed() tells the first apart.
            throw server.failed("list-sessions", result);
        }
        List<RowFormat.Row> rows = format.rows(result.stdout());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TmuxVersion version = TmuxVersion.parse(rows.get(0).text("version"));
        rows = format.rows(TmuxFormats.printed(result.stdout(), version));
        for (String stored : TmuxFormats.storedNames(name, version)) {
            for (RowFormat.Row row : rows) {
                if (row.text("session_name").equals(stored)) {
                    return Optional.of(new SessionId(row.text("session_id")));
                }
            }
        }
        return Optional.empty();
    }
}
