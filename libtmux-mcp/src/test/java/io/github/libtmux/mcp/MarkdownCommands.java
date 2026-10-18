package io.github.libtmux.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The commands this repository's Markdown shows a reader.
 *
 * <p>A command in a README fails for the reader who pastes it, and nothing else reads it. These
 * helpers find the commands, so a test can hold each one to what the program accepts.
 */
final class MarkdownCommands {

    /** Shell operators that end the arguments of the program a command names. */
    private static final Set<String> OPERATORS = Set.of("|", "||", "&&", ";", ">", ">>", "<");

    /** Directories that hold build output or someone else's checkout, never a document to read. */
    private static final Set<String> SKIPPED = Set.of("build", "target", ".gradle", ".git", ".claude", "node_modules");

    private MarkdownCommands() {}

    /**
     * The repository root: the system property the build sets, else the nearest directory above the
     * working directory holding {@code settings.gradle.kts}, which is where an IDE starts a test.
     */
    static Path repository() {
        String configured = System.getProperty("libtmux.docs.root");
        if (configured != null) {
            return Path.of(configured);
        }
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return directory;
    }

    /**
     * The documents a reader acts on: the root README, every module README, and the guides.
     * Decision records and the changelog are history, so a command they once showed is not held to
     * today's program.
     */
    static List<Path> readerFacing(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return SKIPPED.contains(directory.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    boolean readme = file.getFileName().toString().equals("README.md");
                    boolean guide = root.relativize(file).startsWith("docs/guide")
                            && file.toString().endsWith(".md");
                    if (readme || guide) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + root, e);
        }
        found.sort(null);
        return found;
    }

    /**
     * The words of every command a {@code console} fence shows. A command starts at a {@code "$ "}
     * line and runs over the lines that end in a backslash.
     */
    static List<List<String>> consoleCommands(String markdown) {
        List<List<String>> commands = new ArrayList<>();
        boolean inside = false;
        String pending = "";
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inside = trimmed.equals("```console");
                pending = "";
                continue;
            }
            if (!inside) {
                continue;
            }
            if (!pending.isEmpty()) {
                pending += " " + trimmed;
            } else if (trimmed.startsWith("$ ")) {
                pending = trimmed.substring(2);
            } else {
                continue;
            }
            if (pending.endsWith("\\")) {
                pending = pending.substring(0, pending.length() - 1);
                continue;
            }
            commands.add(words(pending));
            pending = "";
        }
        return commands;
    }

    /** The body of every {@code json} fence. */
    static List<String> jsonFences(String markdown) {
        List<String> bodies = new ArrayList<>();
        StringBuilder body = null;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                if (body != null) {
                    bodies.add(body.toString());
                    body = null;
                } else if (trimmed.equals("```json")) {
                    body = new StringBuilder();
                }
            } else if (body != null) {
                body.append(line).append('\n');
            }
        }
        return bodies;
    }

    /** Splits a command line the way a shell would, to the extent a README command needs. */
    static List<String> words(String command) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char c = command.charAt(index);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started || word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(c);
            }
        }
        if (started || word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }

    /**
     * What follows the first word that names {@code program}, up to the first shell operator, or
     * empty when the command does not run it. A path ending in the program's name counts.
     */
    static List<String> argumentsOf(List<String> words, String program) {
        for (int index = 0; index < words.size(); index++) {
            String word = words.get(index);
            if (word.equals(program) || word.endsWith("/" + program)) {
                List<String> rest = words.subList(index + 1, words.size());
                for (int end = 0; end < rest.size(); end++) {
                    if (OPERATORS.contains(rest.get(end))) {
                        return List.copyOf(rest.subList(0, end));
                    }
                }
                return List.copyOf(rest);
            }
        }
        return List.of();
    }

    /** Whether the command runs {@code program} at all. */
    static boolean runs(List<String> words, String program) {
        return words.stream().anyMatch(word -> word.equals(program) || word.endsWith("/" + program));
    }
}
