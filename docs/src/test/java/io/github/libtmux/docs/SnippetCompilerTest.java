package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the isolation a {@code // Given:} line is supposed to give a snippet: exact in both
 * directions, the same property go's {@code isolation_test.go} checks for its own regions.
 *
 * <p>No tmux here. Compiling a snippet never touches a server - only {@link SnippetCompiler#run}
 * does - so these stay fast and belong in the same loop as any other unit test.
 */
final class SnippetCompilerTest {

    private static final SnippetCompiler COMPILER =
            new SnippetCompiler(System.getProperty("libtmux.docs.classpath", ""));

    @Test
    void aBindingDeclaredExactlyCompiles() {
        SnippetCompiler.Compiled compiled = COMPILER.compile(statementSnippet("""
                // Given: Server server
                server.version();
                """));

        assertTrue(compiled.succeeded(), "an exactly declared binding must compile: " + compiled.errors());
        SnippetCompiler.discard(compiled.classes());
    }

    @Test
    void aBindingUsedButNotDeclaredFailsAsCannotFindSymbol() {
        SnippetCompiler.Compiled compiled = COMPILER.compile(statementSnippet("server.version();\n"));

        assertFalse(compiled.succeeded(), "a binding the snippet uses but no Given: names must not compile");
        assertTrue(
                compiled.errors().stream().anyMatch(error -> error.contains("cannot find symbol")),
                "the failure must be javac's own, not something else: " + compiled.errors());
        SnippetCompiler.discard(compiled.classes());
    }

    @Test
    void aBindingDeclaredButNotUsedFailsForThatReason() {
        SnippetCompiler.Compiled compiled = COMPILER.compile(statementSnippet("""
                // Given: Server server
                int unused = 1;
                """));

        assertFalse(compiled.succeeded(), "a Given: binding the snippet never reads must not pass");
        assertTrue(
                compiled.errors().stream()
                        .anyMatch(error -> error.contains("declares 'server'") && error.contains("never uses it")),
                "the failure must name the unused binding: " + compiled.errors());
        SnippetCompiler.discard(compiled.classes());
    }

    @Test
    void aSnippetThatShowsItsImportsCompilesOnThemAlone() {
        SnippetCompiler.Compiled compiled = COMPILER.compile(statementSnippet("""
                import io.github.libtmux.PaneId;

                // Given: Server server
                PaneId id = server.sessions().get(0).windows().get(0).panes().get(0).id();
                """));

        assertTrue(compiled.succeeded(), "imports shown with the snippet must be enough: " + compiled.errors());
        SnippetCompiler.discard(compiled.classes());
    }

    @Test
    void aSnippetThatShowsImportsButUsesAnotherTypeFailsAsCannotFindSymbol() {
        SnippetCompiler.Compiled compiled = COMPILER.compile(statementSnippet("""
                import io.github.libtmux.PaneId;

                // Given: Server server
                TextOutcome seen = TextOutcome.APPEARED;
                PaneId id = server.sessions().get(0).windows().get(0).panes().get(0).id();
                """));

        assertFalse(compiled.succeeded(), "a type the shown imports do not reach must not compile");
        assertTrue(
                compiled.errors().stream().anyMatch(error -> error.contains("cannot find symbol")),
                "the failure must be javac's own, not something else: " + compiled.errors());
        SnippetCompiler.discard(compiled.classes());
    }

    @Test
    void anImportThatNamesNothingFails() {
        SnippetCompiler.Compiled compiled = COMPILER.compile(statementSnippet("""
                import io.github.libtmux.NoSuchType;

                // Given: Server server
                server.version();
                """));

        assertFalse(compiled.succeeded(), "an import of a type that does not exist must not compile");
        SnippetCompiler.discard(compiled.classes());
    }

    private static Snippet statementSnippet(String code) {
        return new Snippet(
                Path.of("SnippetCompilerTest.java"), 1, Snippet.Expectation.RUNS, "", Snippet.Shape.STATEMENTS, code);
    }
}
