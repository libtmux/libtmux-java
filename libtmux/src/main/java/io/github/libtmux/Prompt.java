package io.github.libtmux;

import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import java.util.List;
import kotlin.annotations.jvm.ReadOnly;

/** The server's command-prompt history. */
public final class Prompt {

    private final Server server;

    Prompt(Server server) {
        this.server = server;
    }

    /** What has been typed at the prompt, oldest first. */
    @ReadOnly
    @Operation(Kind.READ)
    public List<String> history() {
        return server.run(List.of("show-prompt-history")).stdout();
    }

    /** Forgets what has been typed at the prompt. */
    @Operation(Kind.MUTATION)
    public void clear() {
        server.run(List.of("clear-prompt-history"));
    }
}
