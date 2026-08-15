package io.github.libtmux.docs;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Puts a documentation snippet in front of javac, and then in front of a real tmux.
 *
 * <p>Compiling proves the API still has the shape the document describes. Running proves the
 * document is right about what happens, which is the part a reader actually depends on and the part
 * a compiler cannot check: a snippet can name every method correctly and still be wrong about the
 * order they go in, or about what tmux does with them.
 */
final class SnippetCompiler {

    /**
     * What every snippet may assume is in scope.
     *
     * <p>Documentation shows the interesting line, not the six before it that made a server, so the
     * six are supplied here and the snippet is compiled as though written after them.
     */
    private static final String PREAMBLE = """
            import static org.junit.jupiter.api.Assertions.*;

            import io.github.libtmux.*;
            import io.github.libtmux.batch.*;
            import io.github.libtmux.control.*;
            import io.github.libtmux.format.*;
            import io.github.libtmux.query.*;
            import io.github.libtmux.snapshot.*;
            import io.github.libtmux.transport.*;
            import io.github.libtmux.jackson.*;
            import io.github.libtmux.junit5.*;
            import io.github.libtmux.mcp.*;
            import io.github.libtmux.workspace.*;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.time.Duration;
            import java.util.*;
            import java.util.concurrent.*;
            import java.util.concurrent.atomic.*;
            import java.util.function.*;
            import java.util.stream.*;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.extension.ExtendWith;
            """;

    /**
     * The scaffolding a set of statements needs before it is a compilation unit.
     *
     * <p>The fields are filled in before the body runs, from a fixture holding a real tmux server on
     * a socket under this port's own root. A snippet that declares its own {@code server} shadows
     * the field, which is what a reader copying it would get anyway.
     */
    private static final String STATEMENT_HARNESS = """
            @SuppressWarnings("all")
            public class DocumentationSnippet {
                public static Server server;
                public static ServerConfig config;
                public static Session session;
                public static Window window;
                public static Pane pane;
                public static Options options;
                public static Path socket;
                public static Path directory;
                public static Duration timeout;
                public static String yamlString;

                // Named in prose as what a caller would do next. What they do is the reader's
                // business; that they are called is the snippet's.
                static void retry() {}
                static void reconcile() {}

                public static void snippet() throws Exception {
            """;

    /** Closes what {@link #STATEMENT_HARNESS} opened, once the snippet has been placed inside it. */
    private static final String HARNESS_TAIL = """

                }
            }
            """;

    private final List<String> classpath;

    SnippetCompiler(String classpath) {
        this.classpath = List.of("-classpath", classpath);
    }

    /** What happened, and where the class files went if anything did. */
    record Compiled(List<String> errors, Path classes) {
        boolean succeeded() {
            return errors.isEmpty();
        }
    }

    /** Compiles a snippet. The caller owns {@link Compiled#classes} and must {@link #discard} it. */
    Compiled compile(Snippet snippet) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system java compiler; run these tests on a JDK");
        }
        String unit = snippet.shape() == Snippet.Shape.TYPE
                ? PREAMBLE + "\n@SuppressWarnings(\"all\")\n" + snippet.code()
                : PREAMBLE + STATEMENT_HARNESS + snippet.code().stripTrailing() + HARNESS_TAIL;

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = temporaryDirectory();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            List<String> options = new ArrayList<>(classpath);
            options.addAll(List.of("-d", classes.toString(), "-proc:none"));
            compiler.getTask(new StringWriter(), files, diagnostics, options, null, List.of(new Source(unit)))
                    .call();
        } catch (IOException e) {
            discard(classes);
            throw new UncheckedIOException("could not run the compiler", e);
        }
        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .toList();
        return new Compiled(errors, classes);
    }

    /**
     * Runs a compiled snippet against the fixture it was given.
     *
     * @param bindings what the harness's fields should hold; a real server, session, pane and socket
     * @throws Throwable whatever the snippet threw, unwrapped, so a failure reads as the snippet's
     */
    void run(Compiled compiled, Map<String, Object> bindings) throws Throwable {
        URL[] where = {toUrl(compiled.classes())};
        try (URLClassLoader loader = new URLClassLoader(where, SnippetCompiler.class.getClassLoader())) {
            Class<?> type = Class.forName("DocumentationSnippet", true, loader);
            for (Map.Entry<String, Object> binding : bindings.entrySet()) {
                type.getField(binding.getKey()).set(null, binding.getValue());
            }
            Method snippet = type.getMethod("snippet");
            try {
                snippet.invoke(null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("could not run the snippet", e);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException e) {
            throw new UncheckedIOException("could not address the snippet's class files", e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("libtmux-docs-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not make somewhere for the snippet's class files", e);
        }
    }

    /** Removes what {@link #compile} wrote, so a suite does not leave class files behind. */
    static void discard(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Best effort; a temporary directory is the operating system's to reclaim.
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove a snippet's class files", e);
        }
    }

    /** The compilation unit, held in memory rather than written somewhere to be cleaned up. */
    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(String code) {
            super(URI.create("string:///DocumentationSnippet.java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
