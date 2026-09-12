package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.TestAbortedException;

/**
 * The arena's refusal to let a documentation snippet stop a server it was only lent, and its
 * exemption for a snippet that needs a fresh server's exact state instead.
 *
 * <p>The fixture stands in for a lent server: {@link DocumentationSnippetsTest#runCase} does not care
 * where its {@link ServerConfig} came from, only whether one is present.
 */
@ExtendWith(TmuxExtension.class)
final class DocumentationArenaTest {

    private static Snippet snippet(String code) {
        return new Snippet(Path.of("synthetic.md"), 1, Snippet.Expectation.RUNS, "", Snippet.Shape.STATEMENTS, code);
    }

    private static ServerConfig lentConfig(Server server, TmuxSocketPath socket) {
        return ServerConfig.builder()
                .binary(server.config().binary())
                .endpoint(ServerEndpoint.socketPath(socket.path()))
                .build();
    }

    private static Set<String> realSnippetLocations() {
        return DocumentationSnippetsTest.snippets().stream().map(Snippet::where).collect(Collectors.toSet());
    }

    @Test
    void aSnippetThatStopsTheServerIsRefusedUnderTheArena(Server server, TmuxSocketPath socket) {
        Optional<ServerConfig> arena = Optional.of(lentConfig(server, socket));

        assertThrows(
                TestAbortedException.class,
                () -> DocumentationSnippetsTest.runCase(snippet("server.killServer();"), server, socket, arena),
                "a snippet that stops the server must be refused under the arena");
        assertTrue(server.hasSession("libtmux"), "the arena's lent server must not have been stopped");
    }

    @Test
    void anOrdinarySnippetStillRunsUnderTheArena(Server server, TmuxSocketPath socket) {
        Optional<ServerConfig> arena = Optional.of(lentConfig(server, socket));

        assertDoesNotThrow(() -> DocumentationSnippetsTest.runCase(snippet("session.name();"), server, socket, arena));
    }

    @Test
    void theSameSnippetActuallyStopsTheServerOutsideTheArena(Server server, TmuxSocketPath socket) {
        assertDoesNotThrow(() ->
                DocumentationSnippetsTest.runCase(snippet("server.killServer();"), server, socket, Optional.empty()));
    }

    @Test
    void everyExemptionNamesASnippetThatStillExists() {
        Set<String> real = realSnippetLocations();

        List<String> stale = DocumentationArena.NEEDS_A_FRESH_SERVER.stream()
                .map(DocumentationArena.Exemption::where)
                .filter(where -> !real.contains(where))
                .toList();

        assertEquals(List.of(), stale, "the exemption list names a case docs-tests no longer finds: " + stale);
    }

    @Test
    void aStaleExemptionEntryIsNotFoundAmongRealSnippets() {
        // The check above can only be trusted once it is shown capable of failing: a made-up entry
        // must not match anything real.
        DocumentationArena.Exemption stale =
                new DocumentationArena.Exemption(Path.of("no-such-file.md"), 9999, "no longer real");

        assertFalse(
                realSnippetLocations().contains(stale.where()),
                "a fabricated entry unexpectedly matched a real snippet");
    }

    @Test
    void aListedCaseRunsAgainstItsOwnServerRatherThanTheLentOne(Server server, TmuxSocketPath socket) throws Throwable {
        Optional<ServerConfig> arena = Optional.of(lentConfig(server, socket));
        Snippet listed = DocumentationSnippetsTest.snippets().stream()
                .filter(candidate -> DocumentationArena.exemptionFor(candidate).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no exemption on the list names a snippet docs-tests found"));
        List<String> before = server.cmd("list-sessions", "-F", "#{session_id}").stdout();

        DocumentationSnippetsTest.runCase(listed, server, socket, arena);

        List<String> after = server.cmd("list-sessions", "-F", "#{session_id}").stdout();
        assertEquals(before, after, "an exempted case must not touch the lent server's own sessions at all");
    }
}
