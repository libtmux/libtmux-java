package io.github.libtmux.docs;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * The imports every snippet may use without writing them - the common ones, not a fixture.
     * What a snippet may read without creating it is {@link #harnessHeader}'s concern, and is
     * exactly what its own {@code // Given:} line asked for.
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
     * A binding a snippet reads but does not create, declared by a {@code // Given:} line: {@code
     * Session session, Window window}. Named for the type a reader would already have to hand, so a
     * reader who has never seen the harness still knows what to build.
     */
    private record Given(String type, String name) {}

    /** The declaration on the first line of a snippet's body, if it has one. */
    private static final Pattern GIVEN_LINE = Pattern.compile("^//\\s*Given:\\s*(.+?)\\s*$");

    /** Closes what {@link #harnessHeader} opened, once the snippet has been placed inside it. */
    private static final String HARNESS_TAIL = """

                }
            }
            """;

    /**
     * A shown result: {@code session.name();  // \u2192 demo}
     *
     * <p>Python's doctest is why the sibling library's README can show what every call returns and
     * still be trusted: the shown value is executed. Java has no doctest, so this is one. A line
     * carrying an arrow becomes an assertion, which means a README cannot claim a value the library
     * does not produce — the number in the comment is as checked as the call above it.
     */
    private static final Pattern SHOWN_RESULT = Pattern.compile("^(\\s*)(.+?);\\s*//\\s*(?:\\u2192|->)\\s*(.*?)\\s*$");

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
        List<Given> given = List.of();
        String unit;
        if (snippet.shape() == Snippet.Shape.TYPE) {
            unit = PREAMBLE + "\n@SuppressWarnings(\"all\")\n" + snippet.code();
        } else {
            given = parseGiven(snippet.code());
            unit = PREAMBLE
                    + harnessHeader(given)
                    + assertShownResults(snippet.code()).stripTrailing()
                    + HARNESS_TAIL;
        }

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
        if (errors.isEmpty() && !given.isEmpty()) {
            errors = unusedGivenErrors(given, snippet.code());
        }
        return new Compiled(errors, classes);
    }

    /**
     * The declarations a {@code // Given:} line names, or none if the snippet's first line is not
     * one - in which case the harness offers nothing, and the snippet must be self-contained.
     *
     * @throws IllegalArgumentException if a declaration is not {@code Type name}
     */
    private static List<Given> parseGiven(String code) {
        String firstLine = code.lines().findFirst().orElse("");
        Matcher line = GIVEN_LINE.matcher(firstLine);
        if (!line.matches()) {
            return List.of();
        }
        List<Given> declared = new ArrayList<>();
        for (String declaration : line.group(1).split(",\\s*", -1)) {
            int space = declaration.trim().lastIndexOf(' ');
            if (space < 0) {
                throw new IllegalArgumentException(
                        "malformed given declaration '" + declaration + "'; expected 'Type name'");
            }
            declared.add(new Given(
                    declaration.trim().substring(0, space), declaration.trim().substring(space + 1)));
        }
        return List.copyOf(declared);
    }

    /**
     * The scaffolding a set of statements needs before it is a compilation unit.
     *
     * <p>Only the bindings a {@code // Given:} line asked for become fields; everything else is left
     * for the snippet to build itself, which is what a reader copying only the fence would have to
     * do too. A snippet that declares its own binding of the same name shadows the field.
     */
    private static String harnessHeader(List<Given> given) {
        StringBuilder header = new StringBuilder();
        header.append("@SuppressWarnings(\"all\")\npublic class DocumentationSnippet {\n");
        for (Given binding : given) {
            header.append("    public static ")
                    .append(binding.type())
                    .append(' ')
                    .append(binding.name())
                    .append(";\n");
        }
        header.append("""

                    // Named in prose as what a caller would do next. What they do is the reader's
                    // business; that they are called is the snippet's.
                    static void retry() {}
                    static void reconcile() {}

                    public static void snippet() throws Exception {
                """);
        return header.toString();
    }

    /**
     * A binding named in a {@code // Given:} line that the snippet's body never reads is exactly as
     * wrong as one it reads and never named: either way the line lies about what the fence needs.
     * javac already catches the first half - an undeclared field is "cannot find symbol" - because
     * {@link #harnessHeader} declares only what was asked for. This catches the second half, which
     * javac has no opinion on: an unused field is not an error in Java the way an unused local is in
     * Go, so the check is textual rather than the compiler's - comments (including the {@code //
     * Given:} line itself) are stripped first, so a name only mentioned in prose does not count.
     */
    private static List<String> unusedGivenErrors(List<Given> given, String code) {
        String body = code.substring(Math.min(code.indexOf('\n') + 1, code.length()));
        String withoutComments = body.lines()
                .map(line -> {
                    int comment = line.indexOf("//");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .reduce("", (all, line) -> all + line + "\n");
        List<String> errors = new ArrayList<>();
        for (Given binding : given) {
            if (!Pattern.compile("\\b" + Pattern.quote(binding.name()) + "\\b")
                    .matcher(withoutComments)
                    .find()) {
                errors.add("given declares '" + binding.name() + "' (" + binding.type()
                        + ") but the snippet never uses it");
            }
        }
        return errors;
    }

    /**
     * Turns every shown result into an assertion.
     *
     * <p>Compared as text, so one rule covers a string, a number, a boolean and a list without the
     * document having to write Java literals in a comment. What a reader sees after the arrow is
     * exactly what {@code toString} produced.
     */
    static String assertShownResults(String code) {
        StringBuilder rewritten = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            Matcher shown = SHOWN_RESULT.matcher(line);
            if (shown.matches() && !shown.group(2).trim().startsWith("//")) {
                rewritten
                        .append(shown.group(1))
                        .append("assertEquals(\"")
                        .append(shown.group(3).replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\", String.valueOf(")
                        .append(shown.group(2))
                        .append("));");
            } else {
                rewritten.append(line);
            }
            rewritten.append('\n');
        }
        return rewritten.toString();
    }

    /**
     * Runs a compiled snippet against the fixture it was given.
     *
     * @param bindings everything the harness could offer - a real server, session, pane and socket;
     *     only the ones the snippet's {@code // Given:} line actually declared exist as fields on
     *     the compiled class, so the rest are skipped rather than failing the run
     * @throws Throwable whatever the snippet threw, unwrapped, so a failure reads as the snippet's
     */
    void run(Compiled compiled, Map<String, Object> bindings) throws Throwable {
        URL[] where = {toUrl(compiled.classes())};
        try (URLClassLoader loader = new URLClassLoader(where, SnippetCompiler.class.getClassLoader())) {
            Class<?> type = Class.forName("DocumentationSnippet", true, loader);
            for (Map.Entry<String, Object> binding : bindings.entrySet()) {
                Field field;
                try {
                    field = type.getField(binding.getKey());
                } catch (NoSuchFieldException notDeclared) {
                    continue; // the Given: line did not ask for this one
                }
                field.set(null, binding.getValue());
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
