package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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

    /** The parity inventories, whose every row names the contract test that row will need. */
    private static final List<String> PARITY = List.of("docs/parity/python-api.md", "docs/parity/test-map.md");

    /**
     * Every way a document spells a coordinate, since a version only checked in one of them is a
     * version wrong in the others.
     */
    private static final List<Pattern> COORDINATES = List.of(
            Pattern.compile("io\\.github\\.libtmux:[a-z0-9-]+:([0-9][^\"'<\\s)]*)"),
            Pattern.compile("\"io\\.github\\.libtmux\"\\s*%%?\\s*\"[a-z0-9-]+\"\\s*%\\s*\"([0-9][^\"]*)\""),
            Pattern.compile("<artifactId>libtmux[a-z0-9-]*</artifactId>\\s*<version>([0-9][^<]*)</version>"),
            Pattern.compile("io\\.github\\.libtmux:[a-z0-9-]+ -> ([0-9][^\\s)]*)"));

    /** Fences this build compiles and runs. A fence in any other source language is not checked. */
    private static final Set<String> EXECUTED = Set.of("java", "kotlin");

    /** Source languages a guide may reasonably carry, whether or not anything here builds them. */
    private static final Set<String> SOURCE = Set.of("java", "kotlin", "scala", "groovy");

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

        List<String> wrong = documents()
                .flatMap(document -> COORDINATES.stream()
                        .flatMap(coordinate -> coordinate
                                .matcher(read(document))
                                .results()
                                .filter(found -> !found.group(1).equals(expected))
                                .map(found -> document + " says " + found.group(1))))
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

    /** An unrun example reads exactly like an executed one, so it has to say which it is. */
    @Test
    void anExampleInALanguageNothingBuildsSaysThatItIsUnchecked() {
        List<String> silent = new ArrayList<>();
        Pattern fence = Pattern.compile("(?:<!--\\s*snippet:[^>]*-->\\s*\\n)?^```([a-z]+)$", Pattern.MULTILINE);
        for (String document : readerFacing()) {
            fence.matcher(read(document)).results().forEach(found -> {
                String language = found.group(1);
                if (SOURCE.contains(language)
                        && !EXECUTED.contains(language)
                        && !found.group().startsWith("<!--")) {
                    silent.add(document + " has an unmarked " + language + " fence");
                }
            });
        }

        assertEquals(List.of(), silent, "an example nothing compiles must carry a snippet directive saying so");
    }

    /** Everything a reader is expected to act on, which is what Documentation.readable also covers. */
    private static List<String> readerFacing() {
        List<String> found = new ArrayList<>(List.of("README.md"));
        PUBLISHED.forEach(module -> found.add(module + "/README.md"));
        found.add("libtmux-bom/README.md");
        try (Stream<Path> guides = Files.list(ROOT.resolve("docs/guide"))) {
            guides.map(guide -> "docs/guide/" + guide.getFileName())
                    .filter(guide -> guide.endsWith(".md"))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not list the guides", e);
        }
        return found;
    }

    /**
     * Those documents name tests in the scheme the tests will use, which is a plan and not a
     * citation. Once the class exists, every name beside it that does not resolve becomes a claim.
     */
    @Test
    void everyContractTestTheParityDocumentsNameIsUnwrittenOrReal() {
        List<String> wrong = new ArrayList<>();
        claimedContractTests()
                .forEach((type, methods) -> sourceOf(type).ifPresent(source -> {
                    String declared = read(ROOT.relativize(source).toString());
                    methods.stream()
                            .filter(method -> !declared.contains(method))
                            .forEach(method -> wrong.add(type + "#" + method));
                }));

        assertEquals(List.of(), wrong, "the parity documents cite a test its own class does not declare");
    }

    /** While those tests are unwritten, each document has to keep saying so where a reader will look. */
    @Test
    void theParityDocumentsCallTheirTestsPlannedWhileTheyAre() {
        for (String document : PARITY) {
            Set<String> named = claimedContractTests().keySet();
            if (named.stream().anyMatch(type -> sourceOf(type).isPresent())) {
                continue;
            }
            assertTrue(read(document).contains("planned parity"), document + " no longer says its tests are planned");
        }
    }

    /** Every {@code Class#method} the parity documents name, grouped by the class that would hold it. */
    private static Map<String, Set<String>> claimedContractTests() {
        Map<String, Set<String>> claimed = new TreeMap<>();
        Pattern cited = Pattern.compile("<code>([A-Z][A-Za-z0-9]*)#([A-Za-z0-9_]+)</code>");
        for (String document : PARITY) {
            cited.matcher(read(document))
                    .results()
                    .forEach(found -> claimed.computeIfAbsent(found.group(1), type -> new TreeSet<>())
                            .add(found.group(2)));
        }
        assertTrue(!claimed.isEmpty(), "the parity documents name no contract tests at all");
        return claimed;
    }

    /** Searched for by file name rather than loaded, since these will not be on this module's path. */
    private static Optional<Path> sourceOf(String type) {
        try (Stream<Path> tree = Files.walk(ROOT)) {
            return tree.filter(path -> !path.toString().contains("/build/"))
                    .filter(path -> path.getFileName().toString().equals(type + ".java")
                            || path.getFileName().toString().equals(type + ".kt"))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("could not search for " + type, e);
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
