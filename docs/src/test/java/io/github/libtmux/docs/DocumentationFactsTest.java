package io.github.libtmux.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * Every module with sources, published or not: a codename is no clearer in a test. Read from the
     * settings, so a module is scanned from the commit that adds it; the two builds the settings do
     * not include are named here.
     */
    private static final List<String> SOURCE_MODULES = Stream.concat(
                    named("include\\(\"([^\":]+)", read("settings.gradle.kts")).stream(),
                    Stream.of("module-tests", "build-logic"))
            .toList();

    /** Published modules, which is what a reader is told to depend on. */
    private static final List<String> PUBLISHED = List.of(
            "libtmux",
            "libtmux-jackson",
            "libtmux-junit5",
            "libtmux-kotlin",
            "libtmux-mcp",
            "libtmux-workspace",
            "libtmux-workspace-cli",
            "libtmux-scala",
            "libtmux-scala-cats",
            "libtmux-scala-ox");

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
        // A numbered entry in a plan or a review, cited by its number, means nothing to anyone
        // without the document it came from, which does not ship.
        Pattern planEntry = Pattern.compile("\\b(?:[Ii]tem|[Rr]uling)s? [0-9]+\\b");
        Pattern shortId = Pattern.compile("\\bD[0-9]{1,2}\\b");
        List<String> found = new ArrayList<>();
        for (String module : SOURCE_MODULES) {
            // Walked whole, minus build output: build-logic and module-tests nest their sources a
            // level deeper, and a module's build script is source too.
            Path sources = ROOT.resolve(module);
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(sources)) {
                for (Path file : tree.filter(Files::isRegularFile)
                        .filter(path -> !ROOT.relativize(path).toString().matches(".*/(build|target)/.*"))
                        .toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java")
                            && !name.endsWith(".kt")
                            && !name.endsWith(".kts")
                            && !name.endsWith(".scala")) {
                        continue;
                    }
                    String text = Files.readString(file);
                    Matcher cited = ticket.matcher(text);
                    while (cited.find()) {
                        found.add(ROOT.relativize(file) + ": " + cited.group());
                    }
                    Matcher entry = planEntry.matcher(text);
                    while (entry.find()) {
                        found.add(ROOT.relativize(file) + ": " + entry.group());
                    }
                    Matcher brief = shortId.matcher(text);
                    while (brief.find()) {
                        int at = brief.start();
                        String around = text.substring(Math.max(0, at - 2), Math.min(text.length(), brief.end() + 1));
                        if (around.startsWith("(") || around.startsWith(" (") || around.endsWith(":")) {
                            found.add(ROOT.relativize(file) + ": " + brief.group());
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
     * workflow and left in the README is a promise nothing keeps. A spelled-out count drifts as
     * lanes are added, so prose says "every supported release" and the ends are checked here.
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
     * Every sbt install line the documentation shows is one the sbt consumer build resolves.
     *
     * <p>That build fetches each artifact from the staged repository exactly as a reader's would, so a
     * documented line it does not carry is a line nothing has shown to work. Versions are compared
     * as a placeholder: the documents say {@code "<version>"} or the release, the build says
     * {@code libtmuxVersion}.
     */
    @Test
    void everyDocumentedSbtLineIsOneTheSbtConsumerResolves() {
        Pattern fence = Pattern.compile(
                "<!--\\s*snippet:\\s*scala-build:\\s*([a-z0-9-]+)\\s*-->\\s*\\n```sbt\\n(.*?)^```",
                Pattern.MULTILINE | Pattern.DOTALL);
        Set<String> consumer = sbtLines(read("module-tests/sbt/build.sbt"));
        List<String> unresolved = new ArrayList<>();
        for (String document : readerFacing()) {
            Matcher found = fence.matcher(read(document));
            while (found.find()) {
                for (String line : sbtLines(found.group(2))) {
                    if (!consumer.contains(line)) {
                        unresolved.add(document + " (" + found.group(1) + "): " + line);
                    }
                }
            }
        }
        assertEquals(List.of(), unresolved, "documented sbt lines the sbt consumer does not resolve");
    }

    /** The {@code libraryDependencies} lines of an sbt fragment, with the version normalized away. */
    private static Set<String> sbtLines(String sbt) {
        return Pattern.compile("libraryDependencies\\s*\\+=[^\\n]*")
                .matcher(sbt)
                .results()
                .map(found -> found.group()
                        .replaceAll("%\\s*(\"[^\"]*\"|libtmuxVersion)\\s*$", "% VERSION")
                        .replaceAll("\\s+", " "))
                .collect(Collectors.toCollection(TreeSet::new));
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

    /**
     * The filtering guide's field table is generated prose: it names every field
     * `field-catalog.tsv` declares, or it is lying about what `Pane_`/`Session_`/`Window_`/`Client_`
     * expose.
     */
    @Test
    void theFilteringGuideListsExactlyTheCatalogsFields() {
        Set<String> catalog = new TreeSet<>();
        for (String line : read("libtmux/src/main/resources/META-INF/io.github.libtmux/field-catalog.tsv")
                .split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\t", -1);
            catalog.add(columns[1] + "." + columns[0]);
        }
        assertTrue(!catalog.isEmpty(), "field-catalog.tsv lists no fields");

        Set<String> guide = new TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\| (Pane|Session|Window|Client) \\| `([A-Za-z]+)` \\|")
                .matcher(read("docs/guide/filtering.md"));
        while (row.find()) {
            guide.add(row.group(1) + "." + row.group(2));
        }
        assertTrue(!guide.isEmpty(), "docs/guide/filtering.md lists no fields");

        assertEquals(catalog, guide, "docs/guide/filtering.md's field table disagrees with field-catalog.tsv");
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
            String title = text.lines()
                    .filter(line -> line.startsWith("# "))
                    .findFirst()
                    .orElse("");
            assertEquals("# " + module, title, module + "'s README does not name it in the first heading");
            assertTrue(
                    text.contains("io.github.libtmux:" + module),
                    module + "'s README never states the coordinate to depend on");
        }
    }

    /**
     * Every example program is in the index that introduces them.
     *
     * <p>The examples exist because nobody compiles what everybody reads first, and an example
     * nothing points at is no better than one nothing runs.
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
        try (Stream<Path> guides = Files.walk(ROOT.resolve("docs/guide"))) {
            guides.map(guide -> ROOT.relativize(guide).toString().replace('\\', '/'))
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

    /** The release attests what it uploads, so a consumer can tie each jar to the commit that made it. */
    @Test
    void theReleaseAttestsEveryArtifactItPublishes() throws IOException {
        String workflow = Files.readString(ROOT.resolve(".github/workflows/release.yml"));
        List<String> unattested = new ArrayList<>();
        for (String module : PUBLISHED) {
            if (!workflow.contains(module + "/build/libs/*.jar")) {
                unattested.add(module);
            }
        }
        if (!workflow.contains("libtmux-bom/build/publications/maven/pom-default.xml")) {
            unattested.add("libtmux-bom");
        }
        assertEquals(List.of(), unattested, "published and not in the release attestation");
    }

    /**
     * A link into this repository that GitHub's own address for the {@code master} branch uses.
     * Maven Central and javadoc.io render no relative link, so the module READMEs spell these out.
     */
    private static final Pattern REPOSITORY_LINK = Pattern.compile(
            "^https://github\\.com/libtmux/libtmux-java/(?:tree|blob)/master(?:/([^#?]*))?(?:[#?].*)?$");

    /**
     * Every link in the repository's Markdown that points into this repository names a file or
     * directory that exists: a relative link, or an absolute one to the {@code master} branch. A
     * link GitHub renders as a 404 is found by a reader first, since nothing else follows it.
     */
    @Test
    void everyMarkdownLinkIntoThisRepositoryResolves() throws IOException {
        List<String> broken = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(ROOT)) {
            for (Path file : tree.filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !ROOT.relativize(path)
                            .toString()
                            .matches("(.*/)?(build|target|\\.gradle|\\.git|\\.claude)/.*"))
                    .toList()) {
                broken.addAll(brokenLinks(file, Files.readString(file)));
            }
        }
        assertEquals(List.of(), broken, "these links into the repository name nothing");
    }

    /** The links in {@code markdown}, as it would be read from {@code file}, that name nothing. */
    private static List<String> brokenLinks(Path file, String markdown) {
        Pattern inline = Pattern.compile("\\]\\(([^)\\s]+)\\)");
        Pattern reference = Pattern.compile("(?m)^\\[[^\\]]+\\]:\\s*\\n?\\s*(\\S+)");
        List<String> broken = new ArrayList<>();
        // Code is not prose: `Resource[IO](config)` in a fence is a call, not a link.
        String text = markdown.replaceAll("(?ms)^\\s*```.*?^\\s*```", "").replaceAll("`[^`\\n]*`", "");
        for (Pattern pattern : List.of(inline, reference)) {
            Matcher link = pattern.matcher(text);
            while (link.find()) {
                Matcher repository = REPOSITORY_LINK.matcher(link.group(1));
                Path target;
                if (repository.matches()) {
                    String path = repository.group(1);
                    target = path == null ? ROOT : ROOT.resolve(path);
                } else {
                    String relative = link.group(1).replaceFirst("#.*", "");
                    if (relative.isEmpty() || relative.matches("[a-z][a-z0-9+.-]*:.*") || relative.startsWith("<")) {
                        continue;
                    }
                    target = file.resolveSibling(relative);
                }
                if (!Files.exists(target)) {
                    broken.add(ROOT.relativize(file) + " -> " + link.group(1));
                }
            }
        }
        return broken;
    }

    /**
     * The link check is only evidence once it can fail: a link to a directory the branch lacks, and
     * a relative one, are both reported, and one that resolves is not.
     */
    @Test
    void theLinkCheckReportsALinkThatNamesNothing() {
        Path readme = ROOT.resolve("README.md");
        String base = "https://github.com/libtmux/libtmux-java/tree/master/";

        assertEquals(List.of(), brokenLinks(readme, "[core](" + base + "libtmux) [root](" + base + ")"));
        assertEquals(List.of(), brokenLinks(readme, "[core][c]\n\n[c]: " + base + "libtmux#packages"));
        assertEquals(
                1,
                brokenLinks(readme, "[gone](" + base + "libtmux-nonexistent)").size());
        assertEquals(
                1,
                brokenLinks(readme, "[gone][c]\n\n[c]: " + base + "libtmux/src/gone")
                        .size());
        assertEquals(1, brokenLinks(readme, "[gone](libtmux-nonexistent/)").size());
        assertEquals(
                List.of(), brokenLinks(readme, "[elsewhere](https://github.com/libtmux/libtmux-go/tree/master/tmux)"));
    }

    /** What a heading that links a directory names, and where it points. */
    private static Map<String, String> directoryHeadings(String markdown) {
        Map<String, String> headings = new TreeMap<>();
        Matcher heading = Pattern.compile(
                        "(?m)^### \\[`([^`]+)`]\\((https://github\\.com/libtmux/libtmux-java/tree/master/[^)\\s]+)\\)")
                .matcher(markdown);
        while (heading.find()) {
            headings.put(heading.group(1), heading.group(2));
        }
        return headings;
    }

    /**
     * The root README heads each module, and the core's README each package it exports, with a
     * heading that links the directory on GitHub. Maven Central renders no README, and GitHub's tree
     * view is where one is found, so the link is absolute.
     */
    @Test
    void theRootAndCoreReadmesHeadEveryModuleAndPackageWithItsDirectory() {
        String tree = "https://github.com/libtmux/libtmux-java/tree/master/";
        Map<String, String> modules = new TreeMap<>();
        PUBLISHED.forEach(module -> modules.put(module, tree + module));
        modules.put("libtmux-bom", tree + "libtmux-bom");
        assertEquals(modules, directoryHeadings(read("README.md")), "the root README's module headings");

        Map<String, String> packages = new TreeMap<>();
        named("exports ([a-z.]+);", read("libtmux/src/main/java/module-info.java"))
                .forEach(name -> packages.put(name, tree + "libtmux/src/main/java/" + name.replace('.', '/')));
        assertFalse(packages.isEmpty(), "libtmux's module descriptor exports nothing");
        assertEquals(packages, directoryHeadings(read("libtmux/README.md")), "the core README's package headings");
    }

    /** The heading check fails on a missing, a misdirected, and an unlinked heading. */
    @Test
    void theHeadingCheckReportsAHeadingThatDoesNotLinkItsDirectory() {
        String tree = "https://github.com/libtmux/libtmux-java/tree/master/";

        assertEquals(
                Map.of("libtmux", tree + "libtmux"),
                directoryHeadings("### [`libtmux`](" + tree + "libtmux): the library"));
        assertEquals(Map.of(), directoryHeadings("### `libtmux`: the library"));
        assertEquals(Map.of(), directoryHeadings("### [`libtmux`](libtmux/): the library"));
        assertEquals(Map.of(), directoryHeadings("## [`libtmux`](" + tree + "libtmux)"));
    }

    /** The READMEs whose prose names the core API, and so must link it. */
    private static final List<String> API_READMES = List.of("README.md", "libtmux/README.md");

    /** Where libtmux.org keeps the reference page for each public type. */
    private static final String REFERENCE = "https://libtmux.org/en/java/latest/reference/";

    /** The slug libtmux.org gives a type's page: the package, then the type's name twice. */
    private static String pageSlug(String javaPackage, String type) {
        String name = type.toLowerCase(Locale.ROOT);
        return javaPackage.replace('.', '-') + "-" + name + "-" + name;
    }

    /**
     * Every public type of the exported packages, with its page slug. The typed fields, {@code
     * Pane_} and the rest, are generated into the build, so they come from the field catalog.
     */
    private static Map<String, String> publicTypePages() throws IOException {
        Map<String, String> pages = new TreeMap<>();
        for (String javaPackage : named("exports ([a-z.]+);", read("libtmux/src/main/java/module-info.java"))) {
            Path directory = ROOT.resolve("libtmux/src/main/java").resolve(javaPackage.replace('.', '/'));
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString().replace(".java", "");
                    Pattern declaration = Pattern.compile(
                            "(?m)^public (?:(?:final|abstract|sealed|non-sealed) )*(?:class|interface|record|enum|@interface) "
                                    + name + "\\b");
                    if (!name.equals("package-info")
                            && declaration.matcher(Files.readString(file)).find()) {
                        pages.put(name, pageSlug(javaPackage, name));
                    }
                }
            }
        }
        for (String line : read("libtmux/src/main/resources/META-INF/io.github.libtmux/field-catalog.tsv")
                .split("\n", -1)) {
            String[] columns = line.strip().split("\t", -1);
            if (!line.isBlank() && !line.startsWith("#") && columns.length > 1) {
                pages.put(columns[1] + "_", pageSlug("io.github.libtmux", columns[1] + "_"));
            }
        }
        return pages;
    }

    /**
     * The public types {@code markdown} names in backticks, outside a fence, and never links to
     * their reference page. A link to one of a type's member pages counts: it lands on the type.
     */
    private static List<String> unlinkedTypes(String markdown, Map<String, String> pages) {
        String prose = markdown.replaceAll("(?ms)^\\s*```.*?^\\s*```", "");
        Map<String, String> definitions = new HashMap<>();
        Matcher definition = Pattern.compile("(?m)^\\[([^\\]]+)\\]:\\s*(\\S+)").matcher(prose);
        while (definition.find()) {
            definitions.put(definition.group(1).toLowerCase(Locale.ROOT), definition.group(2));
        }
        List<String> targets = new ArrayList<>();
        Matcher inline = Pattern.compile("\\]\\(([^)\\s]+)\\)").matcher(prose);
        while (inline.find()) {
            targets.add(inline.group(1));
        }
        // A definition counts only where a link uses its label: an orphan links nothing.
        Matcher used = Pattern.compile("\\]\\[([^\\]]+)\\]").matcher(prose);
        while (used.find()) {
            String target = definitions.get(used.group(1).toLowerCase(Locale.ROOT));
            if (target != null) {
                targets.add(target);
            }
        }
        Set<String> mentioned = new LinkedHashSet<>();
        Matcher span = Pattern.compile("`([^`\\n]+)`").matcher(prose);
        while (span.find()) {
            Matcher identifier =
                    Pattern.compile("(?<![A-Za-z0-9_])[A-Z][A-Za-z0-9]*_?").matcher(span.group(1));
            while (identifier.find()) {
                if (pages.containsKey(identifier.group())) {
                    mentioned.add(identifier.group());
                }
            }
        }
        List<String> unlinked = new ArrayList<>();
        for (String type : mentioned) {
            String page = REFERENCE + pages.get(type);
            if (targets.stream().noneMatch(target -> target.startsWith(page + "/") || target.startsWith(page + "-"))) {
                unlinked.add(type + " is named but never linked to " + page + "/");
            }
        }
        return unlinked;
    }

    /**
     * The first mention of an API is a link to its page, so a reader who meets {@code Server} or
     * {@code Window_} in the opening sections can follow it. Only whether a type is linked at all
     * is checked here, not where, and a mention inside a fence is not prose.
     */
    @Test
    void everyPublicTypeTheApiReadmesNameIsLinkedToItsReferencePage() throws IOException {
        Map<String, String> pages = publicTypePages();
        assertTrue(pages.containsKey("Server") && pages.containsKey("Pane_"), "found only " + pages.keySet());
        List<String> problems = new ArrayList<>();
        for (String readme : API_READMES) {
            unlinkedTypes(read(readme), pages).forEach(problem -> problems.add(readme + ": " + problem));
        }
        assertEquals(List.of(), problems);
    }

    /** The type check fails on an unlinked mention and passes on a linked one, a member page included. */
    @Test
    void theTypeLinkCheckReportsAMentionThatIsNeverLinked() {
        Map<String, String> pages = Map.of(
                "Server", "io-github-libtmux-server-server",
                "Pane_", "io-github-libtmux-pane_-pane_");
        String server = "[`Server`](" + REFERENCE + "io-github-libtmux-server-server/)";
        String member = "[`Server.newSession`](" + REFERENCE + "io-github-libtmux-server-server-newsession/)";

        assertEquals(1, unlinkedTypes("Use `Server` here.", pages).size());
        assertEquals(
                1,
                unlinkedTypes("Use `Pane_.name()` here, and " + server + ".", pages)
                        .size());
        assertEquals(List.of(), unlinkedTypes("Use " + server + " here.", pages));
        assertEquals(List.of(), unlinkedTypes("Use `Server` here, via " + member + ".", pages));
        String definition = "\n\n[s]: " + REFERENCE + "io-github-libtmux-server-server/\n";
        assertEquals(List.of(), unlinkedTypes("Use [`Server`][s] here." + definition, pages));
        // A definition nothing uses links nothing.
        assertEquals(1, unlinkedTypes("Use `Server` here." + definition, pages).size());
        // A fence, a lowercase word, and a longer name are not mentions.
        assertEquals(List.of(), unlinkedTypes("```java\nServer server = null;\n```\n`server` and `MyServer`\n", pages));
    }

    /** A link inside a fence is not a link, so the opening READMEs keep their links in prose. */
    @Test
    void noFenceInTheApiReadmesCarriesALink() {
        Pattern fence = Pattern.compile("(?ms)^```[^\\n]*\\n(.*?)^```");
        for (String readme : API_READMES) {
            Matcher found = fence.matcher(read(readme));
            while (found.find()) {
                assertFalse(
                        found.group(1).contains("](http"),
                        readme + " links from inside a code block: "
                                + found.group(1).lines().findFirst());
            }
        }
        assertTrue(fence.matcher("```java\nx](http://y)\n```\n")
                .results()
                .anyMatch(m -> m.group(1).contains("](http")));
    }

    /**
     * The module list and the package map are the index a reader scans, and the headings below them
     * are additions rather than replacements: each published module keeps its bullet in the root
     * README, and each exported package keeps its bullet in the core's.
     */
    @Test
    void theRootReadmeKeepsItsListOfModules() {
        String root = read("README.md");
        String modules = root.substring(root.indexOf("## Modules\n"), root.indexOf("### [`libtmux`]("));
        Set<String> expectedModules = new TreeSet<>(PUBLISHED);
        expectedModules.add("libtmux-bom");
        assertEquals(
                expectedModules,
                named("(?m)^- \\*\\*\\[`([a-z0-9-]+)`]\\(\\1/\\)\\*\\*", modules),
                "the root README's module list");
    }

    /** The core's package map keeps one bullet for each package the module exports. */
    @Test
    void theCoreReadmeKeepsItsPackageMap() {
        String core = read("libtmux/README.md");
        String map = core.substring(core.indexOf("## Package map\n"), core.indexOf("## Packages\n"));
        assertEquals(
                named("exports ([a-z.]+);", read("libtmux/src/main/java/module-info.java")),
                named("(?m)^- \\*\\*`([a-z.]+)`\\*\\*", map),
                "the core README's package map");
    }

    /** The index check fails when a bullet is replaced by a heading. */
    @Test
    void theIndexCheckReportsAModuleWithoutItsBullet() {
        String bullet = "(?m)^- \\*\\*\\[`([a-z0-9-]+)`]\\(\\1/\\)\\*\\*";

        assertEquals(Set.of("libtmux"), named(bullet, "- **[`libtmux`](libtmux/)** — the library itself.\n"));
        assertEquals(
                Set.of(),
                named(bullet, "### [`libtmux`](https://github.com/libtmux/libtmux-java/tree/master/libtmux)\n"));
        assertEquals(Set.of(), named(bullet, "- **[`libtmux`](libtmux-bom/)** — a link to another directory\n"));
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
