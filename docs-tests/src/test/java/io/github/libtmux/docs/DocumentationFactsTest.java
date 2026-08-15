package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The claims documentation makes that are not code, and go stale just as quietly.
 *
 * <p>A snippet is executed, so it cannot lie. A version written into an install block, or a list of
 * what the platform manages, is prose — and prose is what is still wrong six months later, in the
 * one place every reader starts.
 */
final class DocumentationFactsTest {

    private static final Path ROOT = Path.of(System.getProperty("libtmux.docs.root", "."));

    /** Published modules, which is what a reader is told to depend on. */
    private static final List<String> PUBLISHED = List.of(
            "libtmux", "libtmux-jackson", "libtmux-junit5", "libtmux-kotlin", "libtmux-mcp", "libtmux-workspace");

    private static String read(String path) {
        try {
            return Files.readString(ROOT.resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /** The version in `gradle.properties`, without the development suffix. */
    private static String releaseVersion() {
        Matcher declared =
                Pattern.compile("^libtmuxVersion=(.+)$", Pattern.MULTILINE).matcher(read("gradle.properties"));
        assertTrue(declared.find(), "gradle.properties names no version");
        return declared.group(1).replace("-SNAPSHOT", "");
    }

    /**
     * Every coordinate a document tells someone to paste names the version this build would publish.
     *
     * <p>An install block is the first thing copied and the last thing updated.
     */
    @Test
    void everyInstallSnippetNamesTheCurrentVersion() {
        String expected = releaseVersion();
        Pattern coordinate = Pattern.compile("io\\.github\\.libtmux:[a-z0-9-]+:([0-9][^\"'<\\s)]*)");

        List<String> wrong = documents()
                .flatMap(document -> coordinate
                        .matcher(read(document))
                        .results()
                        .filter(found -> !found.group(1).equals(expected))
                        .map(found -> document + " says " + found.group(1)))
                .toList();

        assertEquals(List.of(), wrong, "install snippets name a version this build does not publish: " + expected);
    }

    /** What the platform's own README says it manages, against what it actually constrains. */
    @Test
    void theBomReadmeListsWhatTheBomManages() {
        Set<String> listed = new TreeSet<>(named("\\[`(libtmux[a-z0-9-]*)`\\]", read("libtmux-bom/README.md")));
        listed.remove("libtmux-bom");
        Set<String> constrained =
                new TreeSet<>(named("api\\(project\\(\":([^\"]+)\"\\)\\)", read("libtmux-bom/build.gradle.kts")));

        assertEquals(constrained, listed, "libtmux-bom's README and its constraints disagree");
        assertEquals(new TreeSet<>(PUBLISHED), constrained, "the platform does not constrain what is published");
    }

    /** Every published module has a README, because Central links people straight to it. */
    @Test
    void everyPublishedModuleIntroducesItself() {
        for (String module : PUBLISHED) {
            Path readme = ROOT.resolve(module).resolve("README.md");
            assertTrue(Files.isRegularFile(readme), module + " has no README");

            String text = read(module + "/README.md");
            assertTrue(text.startsWith("# " + module + "\n"), module + "'s README does not name it first");
            assertTrue(
                    text.contains("io.github.libtmux:" + module),
                    module + "'s README never states the coordinate to depend on");
        }
    }

    private static Stream<String> documents() {
        Stream<String> packages = PUBLISHED.stream().map(module -> module + "/README.md");
        return Stream.concat(
                Stream.concat(Stream.of("README.md", "libtmux-bom/README.md"), packages),
                Stream.of("docs/guide/kotlin.md", "docs/guide/scala.md", "RELEASING.md"));
    }

    private static Set<String> named(String pattern, String text) {
        return Pattern.compile(pattern)
                .matcher(text)
                .results()
                .map(found -> found.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
