package io.github.libtmux;

import java.util.List;

/** The commands this tmux knows, read without starting a server to answer. */
public final class Commands {

    private final Server server;

    Commands(Server server) {
        this.server = server;
    }

    /** Every command, as tmux prints it. */
    public List<String> list() {
        return server.withoutStartingServer(List.of("list-commands")).stdout();
    }
}
