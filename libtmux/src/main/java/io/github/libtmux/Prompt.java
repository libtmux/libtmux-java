package io.github.libtmux;

import java.util.List;
import kotlin.annotations.jvm.ReadOnly;

/**
 * The server's command-prompt history.
 *
 * <p>Whether the commands exist is asked of the daemon, not inferred from a version string.
 */
public final class Prompt {

    /** The release a refusal names. The daemon's command list is what actually decides. */
    private static final TmuxVersion SINCE = new TmuxVersion(3, 3, "");

    private final Server server;

    Prompt(Server server) {
        this.server = server;
    }

    /**
     * What has been typed at the prompt, oldest first.
     *
     * @throws UnsupportedTmuxVersionException if this tmux has no such command
     */
    @ReadOnly
    public List<String> history() {
        require();
        return server.run(List.of("show-prompt-history")).stdout();
    }

    /**
     * Forgets what has been typed at the prompt.
     *
     * @throws UnsupportedTmuxVersionException if this tmux has no such command
     */
    public void clear() {
        require();
        server.run(List.of("clear-prompt-history"));
    }

    private void require() {
        if (server.commands().list().stream().anyMatch(line -> named(line, "show-prompt-history"))) {
            return;
        }
        throw new UnsupportedTmuxVersionException("the command prompt's history", SINCE, server.version());
    }

    /** Whether a {@code list-commands} line names this command, not one of its aliases. */
    private static boolean named(String line, String name) {
        int space = line.indexOf(' ');
        return (space < 0 ? line : line.substring(0, space)).equals(name);
    }
}
