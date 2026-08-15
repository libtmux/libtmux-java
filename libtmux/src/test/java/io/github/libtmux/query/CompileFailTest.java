package io.github.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the typed field handles actually reject nonsense at compile time.
 *
 * <p>"Invalid operations fail to compile" is the whole justification for typed handles over a
 * stringly API, and it is not observable from a passing test suite — code that does not compile
 * cannot be written in the test sources at all. So the compiler is invoked directly: each case below
 * must be rejected, and a control case must be accepted, or the claim is unfalsifiable.
 */
final class CompileFailTest {

    @Test
    @Timeout(120)
    void aValidExpressionCompiles() {
        assertTrue(
                compiles(
                        "io.github.libtmux.query.FilterExpr<Model.Pane> e = Model.Pane_.command().startsWith(\"nv\");"),
                "the control must compile, or every rejection below proves nothing");
    }

    @Test
    @Timeout(120)
    void textOperatorsAreUnreachableFromANumberField() {
        assertFalse(
                compiles("Object e = Model.Pane_.index().startsWith(\"nv\");"),
                "startsWith must not exist on a number field");
        assertFalse(
                compiles("Object e = Model.Pane_.index().contains(\"nv\");"),
                "contains must not exist on a number field");
    }

    @Test
    @Timeout(120)
    void orderingOperatorsAreUnreachableFromATextField() {
        assertFalse(
                compiles("Object e = Model.Pane_.command().greaterThan(3);"),
                "greaterThan must not exist on a text field");
    }

    @Test
    @Timeout(120)
    void textOperatorsAreUnreachableFromAFlagField() {
        assertFalse(
                compiles("Object e = Model.Pane_.active().contains(\"x\");"),
                "contains must not exist on a flag field");
    }

    @Test
    @Timeout(120)
    void aRelationCannotBeUsedWithoutAQuantifier() {
        assertFalse(
                compiles("io.github.libtmux.query.FilterExpr<Model.Window> e = Model.Window_.panes();"),
                "a to-many relation is not itself a filter");
    }

    @Test
    @Timeout(120)
    void aQuantifierRejectsAPredicateOverTheWrongType() {
        assertFalse(
                compiles("Object e = Model.Window_.panes().any(Model.Window_.name().is(\"x\"));"),
                "a pane quantifier must not accept a window predicate");
    }

    // -------------------------------------------------------------------------------------------
    // Canonical provenance must be unforgeable, which is only observable from another package.
    // -------------------------------------------------------------------------------------------

    @Test
    @Timeout(120)
    void anOutsiderCanBuildADerivedField() {
        assertTrue(
                compilesOutside("Object f = io.github.libtmux.query.Fields.text(\"command\", (String s) -> s);"),
                "the control must compile, or every rejection below proves nothing");
    }

    @Test
    @Timeout(120)
    void anOutsiderCannotMintCanonicalProvenance() {
        assertFalse(
                compilesOutside("Object p = io.github.libtmux.query.FieldProvenance.Canonical.INSTANCE;"),
                "the canonical instance must not be reachable from outside the package");
        assertFalse(
                compilesOutside("Object p = new io.github.libtmux.query.FieldProvenance.Canonical();"),
                "the canonical constructor must be private");
    }

    @Test
    @Timeout(120)
    void anOutsiderCannotMintACanonicalField() {
        assertFalse(
                compilesOutside(
                        "Object f = io.github.libtmux.query.FieldRef.canonical(\"c\", io.github.libtmux.query.FieldKind.TEXT,"
                                + " (String s) -> s);"),
                "canonical field minting must be package-private");
    }

    @Test
    @Timeout(120)
    void anOutsiderCannotImplementTheProvenanceInterface() {
        assertFalse(
                compilesOutside("class Forged implements io.github.libtmux.query.FieldProvenance {"
                        + " public boolean lowerable() { return true; } }"),
                "FieldProvenance is sealed, so no third implementation exists");
    }

    // -------------------------------------------------------------------------------------------

    /** Compiles one statement against the test classpath and reports whether javac accepted it. */
    private static boolean compiles(String statement) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system java compiler; run tests on a JDK");
        }
        String source = """
                package io.github.libtmux.query;

                final class CompileProbe {
                    void probe() {
                        %s
                    }
                }
                """.formatted(statement);
        return compileSource("io/github/libtmux/query", source);
    }

    private static boolean compileSource(String packagePath, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system java compiler; run tests on a JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // Its own directory, removed afterwards. Writing class files straight into the temporary
        // directory left them there for good, under the root this port reserves for tmux sockets,
        // and gave two probes running at once the same path to write.
        Path classes = createProbeDirectory();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            List<String> options =
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString());
            return compiler.getTask(
                            new StringWriter(),
                            files,
                            diagnostics,
                            options,
                            null,
                            List.of(new InMemorySource(packagePath, source)))
                    .call();
        } catch (Exception e) {
            throw new IllegalStateException("could not run the compiler probe", e);
        } finally {
            deleteTree(classes);
        }
    }

    private static Path createProbeDirectory() {
        try {
            return Files.createTempDirectory("libtmux-probe-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not make somewhere for the probe's class files", e);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort; what is left is a directory the operating system will reclaim.
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove the probe's class files", e);
        }
    }

    /** Same probe, but from a package that has no privileged access to {@code io.github.libtmux.query}. */
    private static boolean compilesOutside(String body) {
        return compileSource("outsider", """
                package outsider;

                final class CompileProbe {
                    void probe() {
                        %s
                    }
                }
                """.formatted(body));
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        InMemorySource(String packagePath, String source) {
            super(URI.create("string:///" + packagePath + "/CompileProbe.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
