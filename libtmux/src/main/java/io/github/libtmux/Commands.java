package io.github.libtmux;

import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import java.util.List;
import kotlin.annotations.jvm.ReadOnly;

/** The commands this tmux knows, read without starting a server to answer. */
public final class Commands {

    private final Server server;

    Commands(Server server) {
        this.server = server;
    }

    /** Every command, as tmux prints it. */
    @ReadOnly
    @Operation(Kind.READ)
    public List<String> list() {
        return server.withoutStartingServer(List.of("list-commands")).stdout();
    }
}
