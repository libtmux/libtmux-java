package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.libtmux.Options;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.batch.BatchResult;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Text that survives the trip to tmux unchanged.
 *
 * <p>tmux ends a command at an argument whose last byte is {@code ;}, keeping the argument up to it,
 * so a value ending in a semicolon is truncated rather than refused. Every value below ends in one.
 */
@ExtendWith(TmuxExtension.class)
final class ArgvIntegrityIntegrationTest {

    @Test
    void aWindowNameKeepsItsTrailingSemicolon(Server server) {
        Window window = session(server).newWindow("placeholder");

        assertEquals("build;", window.rename("build;").name());
    }

    @Test
    void anOptionValueKeepsItsTrailingSemicolon(Server server) {
        Options options = session(server).options();

        options.set("status-left", "one;");

        assertEquals(Optional.of("one;"), options.get("status-left"));
    }

    @Test
    void aSessionNameKeepsItsTrailingSemicolon(Server server) {
        assertEquals("work;", server.newSession("work;").name());
    }

    @Test
    void aPaneTitleKeepsItsTrailingSemicolon(Server server) {
        assertEquals(
                "shell;",
                session(server).activePane().orElseThrow().retitle("shell;").title());
    }

    /** A batch separates its operations structurally, so caller text cannot end one early. */
    @Test
    void anOperationEndingInASemicolonDoesNotEndTheBatch(Server server) {
        Window window = session(server).newWindow("placeholder");

        BatchResult results = server.batch()
                .add("rename-window", "-t", window.id().value(), "named;")
                .add("display-message", "-p", "-t", window.id().value(), "#{window_name}")
                .run();

        assertEquals(2, results.operations().size());
        assertEquals("named;", results.operations().get(1).stdout().get(0));
    }

    private static Session session(Server server) {
        return server.sessions().get(0);
    }
}
