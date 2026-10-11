package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Complete common API programs, executed by their displayed {@code api/run.sh}
 * launcher.
 */
final class ApiProgramsTest {
    private static final Path ROOT = Path.of("..");
    private static final String API_PACKAGE = "io/github/libtmux/examples/api";

    static Stream<JsonNode> programs() throws IOException {
        JsonNode examples = manifest().required("examples");
        return StreamSupport.stream(examples.spliterator(), false);
    }

    static Stream<Arguments> cases() throws IOException {
        return programs().map(ApiProgramsTest::named);
    }

    private static Arguments named(JsonNode program) {
        String id = program.required("id").asText();
        return Arguments.of(Named.named(id, program));
    }

    private static JsonNode manifest() throws IOException {
        Path manifest = Path.of("api/manifest.json");
        return new ObjectMapper().readTree(manifest.toFile());
    }

    @Test
    void manifestCoversEveryWholeProgramAndSetupFile() throws Exception {
        JsonNode catalog = manifest();
        assertEquals(1, catalog.required("schemaVersion").asInt());
        var variants = Set.of("java", "kotlin", "scala-direct", "scala-cats");
        var listings = Set.of("ListSessions", "ListWindows", "ListPanes");
        var reads = Set.of("Connect", "Query", "Capture");
        var creates = Set.of("NewSession", "NewWindow");
        Set<String> expected = new HashSet<>();
        for (String variant : variants) {
            for (var group : List.of(listings, reads, creates)) {
                group.forEach(program -> expected.add(variant + "-" + program));
            }
        }
        Set<String> actual = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode program : programs().toList()) {
            String id = program.required("id").asText();
            assertTrue(actual.add(id), "duplicate example id");
            String source = program.required("sourceFile").asText();
            assertTrue(source.startsWith("examples/src/main/"), source);
            assertFalse(source.contains(".."), source);
            assertTrue(paths.add(source), "one source file per whole program");
            assertTrue(Files.isRegularFile(ROOT.resolve(source)), source);
            String main = program.required("mainClass").asText();
            Class.forName(main).getMethod("main", String[].class);
            assertFalse(program.required("description").asText().isBlank());
            assertFalse(program.required("expectedOutput").asText().isBlank());
            assertFalse(program.required("targets").isEmpty());
            String path = program.required("path").asText();
            assertTrue(path.startsWith("src/main/"));
        }
        assertEquals(expected, actual);
        assertEquals(sourceFiles(), paths, "every program must be attached");
        JsonNode described = catalog.required("variants");
        Set<String> names = new HashSet<>();
        described.fieldNames().forEachRemaining(names::add);
        assertEquals(variants, names);
        for (JsonNode variant : described) {
            Set<String> destinations = new HashSet<>();
            for (JsonNode file : variant.required("files")) {
                String source = file.required("sourceFile").asText();
                assertTrue(Files.isRegularFile(ROOT.resolve(source)));
                String path = file.required("path").asText();
                assertTrue(destinations.add(path), "duplicate setup file");
            }
            assertEquals(setupFiles(), destinations);
        }
    }

    private static Set<String> setupFiles() {
        Set<String> files = new HashSet<>();
        files.addAll(List.of("settings.gradle.kts", "build.gradle.kts"));
        files.addAll(List.of("gradle.properties", "run.sh"));
        return files;
    }

    private static Set<String> sourceFiles() throws IOException {
        Set<String> sources = new HashSet<>();
        for (String language : Set.of("java", "kotlin", "scala")) {
            Path root = Path.of("src/main", language, API_PACKAGE);
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> found = files.filter(Files::isRegularFile).toList();
                for (Path file : found) {
                    sources.add("examples/" + file);
                }
            }
        }
        return sources;
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void mainPrintsDocumentedOutput(JsonNode program) throws Exception {
        String main = program.required("mainClass").asText();
        String output = MainsTest.launchApiProgram(main);
        assertEquals(program.required("expectedOutput").asText(), output);
    }
}
