package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** Every module with sources, published or not: a codename is no clearer in a test. */
    private static final List<String> SOURCE_MODULES = List.of(
            "libtmux",
            "libtmux-jackson",
            "libtmux-junit5",
            "libtmux-kotlin",
            "libtmux-mcp",
            "libtmux-workspace",
            "benchmarks",
            "docs-tests",
            "examples",
            "integration-tests");

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
     * An internal tracker id in any source is a reference a reader cannot follow.
     *
     * <p>They arrive honestly — a comment written while a ticket was open, a marker string named after
     * the ticket that needed it — and then stay. The sentence around one always says the thing anyway,
     * so the tag is pure cost to everyone outside the project. Tests too: a test is where a reader goes
     * to learn why a behaviour exists, and a codename there answers nothing.
     */
    @Test
    void noSourceCitesAnInternalTrackerId() throws IOException {
        // A ticket-shaped id is one wherever it sits. A short D-number could be a dimension or a
        // drive, so that one counts only where it reads as a citation.
        Pattern ticket = Pattern.compile("\\bJAVA[0-9]*-[A-Z0-9]+");
        Pattern shortId = Pattern.compile("\\bD[0-9]{1,2}\\b");
        List<String> found = new ArrayList<>();
        for (String module : SOURCE_MODULES) {
            for (String set : List.of("src/main", "src/test")) {
                Path sources = ROOT.resolve(module).resolve(set);
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(sources)) {
                    for (Path file : tree.filter(Files::isRegularFile).toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".kts")) {
                            continue;
                        }
                        String text = Files.readString(file);
                        Matcher cited = ticket.matcher(text);
                        while (cited.find()) {
                            found.add(ROOT.relativize(file) + ": " + cited.group());
                        }
                        Matcher brief = shortId.matcher(text);
                        while (brief.find()) {
                            int at = brief.start();
                            String around =
                                    text.substring(Math.max(0, at - 2), Math.min(text.length(), brief.end() + 1));
                            if (around.startsWith("(") || around.startsWith(" (") || around.endsWith(":")) {
                                found.add(ROOT.relativize(file) + ": " + brief.group());
                            }
                        }
                    }
                }
            }
        }

        assertEquals(List.of(), found, "a source cites an internal tracker id");
    }

    /**
     * The supported range is the README's strongest claim, and the only one a reader cannot check.
     *
     * <p>"That range is not a claim" is true exactly while the matrix runs its ends. A release
     * added to the workflow and not to the README understates what is tested; one removed from the
     * workflow and left in the README is a promise nothing keeps. A spelled-out count is worse
     * again — it was "eight" against a nine-lane matrix — so prose says "every supported release"
     * and the ends are checked here.
     */
    @Test
    void theReadmeNamesTheEndsOfTheMatrixItClaims() {
        Matcher lanes = Pattern.compile("tmux:\\s*\\[([^\\]]+)]").matcher(read(".github/workflows/tmux-matrix.yml"));
        assertTrue(lanes.find(), "the matrix workflow names no tmux versions");
        List<String> running = Pattern.compile("'([^']+)'")
                .matcher(lanes.group(1))
                .results()
                .map(found -> found.group(1))
                .toList();
        assertTrue(running.size() > 1, "a range needs two ends: " + running);

        String readme = read("README.md");
        String claimed = running.get(0) + " through " + running.get(running.size() - 1);

        assertTrue(readme.contains(claimed), "the README does not claim the range the matrix runs: " + claimed);
        assertFalse(
                Pattern.compile("\\b(?:five|six|seven|eight|nine|ten|eleven|twelve)\\s+supported\\s+releases\\b")
                        .matcher(readme)
                        .find(),
                "the README spells out a release count, which drifts the moment a lane is added");
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

    /**
     * Every example program is in the index that introduces them.
     *
     * <p>The examples exist because nobody compiles what everybody reads first, and an example
     * nothing points at is no better than one nothing runs. Two had been written and never listed
     * when this gate was added.
     */
    @Test
    void everyExampleIsListedWhereExamplesAreIntroduced() throws IOException {
        Path programs = ROOT.resolve("examples/src/main/java/io/github/libtmux/examples");
        String index = read("examples/README.md");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> found = Files.list(programs)) {
            found.filter(file -> file.getFileName().toString().endsWith(".java"))
                    .map(file -> file.getFileName().toString().replace(".java", ""))
                    .filter(name -> !index.contains("`" + name + "`"))
                    .forEach(missing::add);
        }
        assertEquals(List.of(), missing, "examples/README.md does not list every example");
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
        List<String> found = new ArrayList<>(List.of("README.md", "MIGRATION.md"));
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
            if (!anyContractTestStillUnwritten(claimedContractTests(List.of(document)))) {
                continue;
            }
            assertTrue(read(document).contains("planned parity"), document + " no longer says its tests are planned");
        }
    }

    /**
     * One written contract test must not clear the document of naming the rest as planned.
     *
     * <p>{@code ServerTest} exists, so a document citing it alongside a still-unwritten class has to
     * keep saying "planned parity" for the one that is; a check that stops at the first resolved
     * class would miss that.
     */
    @Test
    void aWrittenContractTestDoesNotClearAStillPlannedSibling() {
        Map<String, Set<String>> mixed = new TreeMap<>();
        mixed.put("ServerTest", Set.of("liveReadsRejectAnAbsentDaemon"));
        mixed.put("NoSuchDocsFixtureContract", Set.of("aPlannedMethod"));

        assertTrue(anyContractTestStillUnwritten(mixed), "a real class must not mask an unwritten sibling");
    }

    /** The clean control: once every cited class is real, nothing is left to call planned. */
    @Test
    void everyContractTestBeingWrittenClearsThePlannedRequirement() {
        Map<String, Set<String>> allWritten = new TreeMap<>();
        allWritten.put("ServerTest", Set.of("liveReadsRejectAnAbsentDaemon"));

        assertFalse(anyContractTestStillUnwritten(allWritten), "an all-real citation set still reads as unwritten");
    }

    /** Whether a contract test the map cites has no matching source yet. */
    private static boolean anyContractTestStillUnwritten(Map<String, Set<String>> claimed) {
        return claimed.keySet().stream().anyMatch(type -> sourceOf(type).isEmpty());
    }

    /** Every {@code Class#method} the parity documents name, grouped by the class that would hold it. */
    private static Map<String, Set<String>> claimedContractTests() {
        return claimedContractTests(PARITY);
    }

    /** As {@link #claimedContractTests()}, but read from only the given documents. */
    private static Map<String, Set<String>> claimedContractTests(List<String> documents) {
        Map<String, Set<String>> claimed = new TreeMap<>();
        Pattern cited = Pattern.compile("<code>([A-Z][A-Za-z0-9]*)#([A-Za-z0-9_]+)</code>");
        for (String document : documents) {
            cited.matcher(read(document))
                    .results()
                    .forEach(found -> claimed.computeIfAbsent(found.group(1), type -> new TreeSet<>())
                            .add(found.group(2)));
        }
        assertTrue(!claimed.isEmpty(), "the parity documents name no contract tests at all");
        return claimed;
    }

    /** A tag can be moved to other code after review; a commit cannot. Dependabot keeps them current. */
    @Test
    void everyWorkflowActionIsPinnedToACommit() throws IOException {
        List<String> unpinned = new java.util.ArrayList<>();
        try (Stream<Path> workflows = Files.list(ROOT.resolve(".github/workflows"))) {
            for (Path workflow : workflows.sorted().toList()) {
                Pattern.compile("(?m)^\\s*-?\\s*uses:\\s*(\\S+)")
                        .matcher(Files.readString(workflow))
                        .results()
                        .map(found -> found.group(1))
                        .filter(action -> !action.startsWith("./") && !action.matches(".+@[0-9a-f]{40}"))
                        .forEach(action -> unpinned.add(workflow.getFileName() + ": " + action));
            }
        }
        assertTrue(unpinned.isEmpty(), "pin these to a commit sha: " + unpinned);
    }

    /** Searched for by file name rather than loaded, since these will not be on this module's path. */
    @Test
    void theReleaseAttestationIncludesTheBomPom() throws IOException {
        String workflow = Files.readString(ROOT.resolve(".github/workflows/release.yml"));
        assertTrue(
                workflow.contains("libtmux-bom/build/publications/maven/pom-default.xml"),
                "the BOM pom is published and is not in the attestation");
    }

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
