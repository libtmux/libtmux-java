package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.libtmux.Options;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every Java snippet in the documentation, compiled and then run against a real tmux.
 *
 * <p>Snippets are the part of a project people copy and the part nothing compiles. They go stale
 * silently, and a stale snippet is worse than no snippet: it reads exactly as well as a working one.
 *
 * <p>One case per snippet, named for the file and line it came from, so a failure says where to look
 * rather than that "the docs" are broken. One tmux server per case, from {@link TmuxExtension},
 * which puts every socket under this port's own root and removes it afterwards — a snippet that
 * makes a session gets a server nobody else is using, and cannot disturb the next one.
 */
@ExtendWith(TmuxExtension.class)
final class DocumentationSnippetsTest {

    private static final Path ROOT = Path.of(System.getProperty("libtmux.docs.root", "."));

    static List<Snippet> snippets() {
        List<Snippet> found = new ArrayList<>();
        for (Path document : Documentation.readable(ROOT)) {
            found.addAll(Documentation.snippetsIn(ROOT, document));
        }
        return found;
    }

    /**
     * The suite is worthless if it discovers nothing, and a filter or a rename can reduce it to
     * nothing without failing. So the count is asserted before the cases are believed.
     */
    @Test
    void theDocumentationIsActuallyBeingRead() {
        List<Snippet> found = snippets();

        assertTrue(found.size() >= 40, "only found " + found.size() + " snippets; the extractor has stopped working");
        assertTrue(
                found.stream().anyMatch(snippet -> snippet.file().toString().startsWith("libtmux/")),
                "the core's own README contributed nothing");
        assertTrue(
                found.stream().anyMatch(snippet -> snippet.expectation() == Snippet.Expectation.RUNS),
                "nothing is being run, so nothing is really being checked");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    void theSnippetIsWhatItClaimsToBe(Snippet snippet, Server server, TmuxSocketPath socket) throws Throwable {
        if (snippet.expectation() == Snippet.Expectation.SKIPPED) {
            return;
        }
        SnippetCompiler compiler = new SnippetCompiler(System.getProperty("libtmux.docs.classpath", ""));
        SnippetCompiler.Compiled compiled = compiler.compile(snippet);
        try {
            if (snippet.expectation() == Snippet.Expectation.DOES_NOT_COMPILE) {
                assertFalse(
                        compiled.succeeded(),
                        snippet.where() + " compiles, but the documentation says the compiler rejects it");
                return;
            }
            if (!compiled.succeeded()) {
                fail(snippet.where() + " does not compile:\n  " + String.join("\n  ", compiled.errors()) + "\n"
                        + indented(snippet.code()));
            }
            if (snippet.shape() != Snippet.Shape.STATEMENTS) {
                return; // A type declaration has nothing to run; compiling it is the whole check.
            }
            if (snippet.expectation() == Snippet.Expectation.THROWS) {
                Throwable thrown = null;
                try {
                    compiler.run(compiled, bindings(server, socket));
                } catch (Throwable e) {
                    thrown = e;
                }
                assertNotNull(
                        thrown, snippet.where() + " ran without failing, but the documentation says it is refused");
                assertEquals(
                        snippet.detail(),
                        thrown.getClass().getSimpleName(),
                        snippet.where() + " failed with the wrong thing: " + thrown);
                return;
            }
            if (snippet.expectation() == Snippet.Expectation.RUNS) {
                compiler.run(compiled, bindings(server, socket));
            }
        } finally {
            SnippetCompiler.discard(compiled.classes());
        }
    }

    /** What the harness's fields hold: a running server and the things a reader would already have. */
    private static Map<String, Object> bindings(Server server, TmuxSocketPath socket) {
        Session session = server.sessions().get(0);
        Window window = session.windows().get(0);
        Pane pane = window.panes().get(0);
        Options options = session.options();

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("server", server);
        bindings.put("config", server.config());
        bindings.put("session", session);
        bindings.put("window", window);
        bindings.put("pane", pane);
        bindings.put("options", options);
        bindings.put("socket", socket.path());
        bindings.put("directory", socket.path().getParent());
        bindings.put("timeout", Duration.ofSeconds(5));
        bindings.put("yamlString", "session_name: from-a-snippet\nwindows:\n  - window_name: one\n");
        return bindings;
    }

    private static String indented(String code) {
        return code.lines().map(line -> "    " + line).reduce("", (all, line) -> all + line + "\n");
    }
}
