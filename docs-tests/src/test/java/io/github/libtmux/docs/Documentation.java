package io.github.libtmux.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the Java in the documentation.
 *
 * <p>Only the documents a reader is expected to act on. Notes under {@code docs/spikes},
 * {@code docs/plans} and {@code docs/studies} are dated records of what was measured or decided at
 * the time; holding them to today's API would either break the build or quietly rewrite history,
 * and neither is what a record is for.
 */
final class Documentation {

    /** A fenced block, and the directive comment that may precede it. */
    private static final Pattern FENCE = Pattern.compile(
            "(?:<!--\\s*snippet:\\s*([^>]*?)\\s*-->\\s*\\n)?^```java\\n(.*?)^```", Pattern.MULTILINE | Pattern.DOTALL);

    /** A block that already declares a type, rather than statements needing somewhere to live. */
    private static final Pattern DECLARES_A_TYPE = Pattern.compile(
            "^\\s*(?:@\\w+[^\\n]*\\n\\s*)*(?:public\\s+|final\\s+|abstract\\s+)*"
                    + "(?:class|record|interface|enum)\\s+\\w",
            Pattern.MULTILINE);

    private Documentation() {}

    /** Every document whose code is meant to work today. */
    static List<Path> readable(Path root) {
        List<Path> found = new ArrayList<>();
        found.add(root.resolve("README.md"));
        try (Stream<Path> packages = Files.list(root)) {
            packages.map(directory -> directory.resolve("README.md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not list the repository root", e);
        }
        Path guides = root.resolve("docs/guide");
        try (Stream<Path> written = Files.list(guides)) {
            written.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + guides, e);
        }
        return List.copyOf(found);
    }

    /** Every Java block in one document, in the order a reader meets them. */
    static List<Snippet> snippetsIn(Path root, Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        List<Snippet> snippets = new ArrayList<>();
        Matcher fence = FENCE.matcher(text);
        while (fence.find()) {
            String directive = fence.group(1);
            String code = fence.group(2);
            snippets.add(new Snippet(
                    root.relativize(file),
                    lineOf(text, fence.start()),
                    expectationOf(directive, file),
                    detailOf(directive),
                    DECLARES_A_TYPE.matcher(code).find() ? Snippet.Shape.TYPE : Snippet.Shape.STATEMENTS,
                    code));
        }
        return List.copyOf(snippets);
    }

    private static Snippet.Expectation expectationOf(String directive, Path file) {
        if (directive == null) {
            return Snippet.Expectation.RUNS;
        }
        if (directive.equals("does-not-compile")) {
            return Snippet.Expectation.DOES_NOT_COMPILE;
        }
        if (directive.startsWith("throws:") && directive.length() > "throws:".length()) {
            return Snippet.Expectation.THROWS;
        }
        if (directive.startsWith("compile-only:") && directive.length() > "compile-only:".length()) {
            return Snippet.Expectation.COMPILES;
        }
        if (directive.startsWith("skip:") && directive.length() > "skip:".length()) {
            return Snippet.Expectation.SKIPPED;
        }
        // A directive nobody recognises is a snippet nobody is checking, which is the state this
        // exists to prevent. Naming the file because a typo here is silent otherwise.
        throw new IllegalArgumentException("unknown snippet directive '" + directive + "' in " + file
                + "; expected 'does-not-compile', 'throws: <exception>', 'compile-only: <reason>'"
                + " or 'skip: <reason>'");
    }

    /** Whatever followed the colon: an exception's simple name, or a reason nobody parses. */
    private static String detailOf(String directive) {
        if (directive == null) {
            return "";
        }
        int colon = directive.indexOf(':');
        return colon < 0 ? "" : directive.substring(colon + 1).trim();
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }
}
