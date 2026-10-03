package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Complete common API programs, executed by their displayed {@code api/run.sh} launcher. */
final class ApiProgramsTest {
    private static final Path ROOT = Path.of("..");

    static Stream<JsonNode> programs() throws IOException {
        return StreamSupport.stream(manifest().required("examples").spliterator(), false);
    }

    static Stream<Arguments> cases() throws IOException {
        return programs()
                .map(program -> Arguments.of(named(program.required("id").asText(), program)));
    }

    private static JsonNode manifest() throws IOException {
        return new ObjectMapper().readTree(Path.of("api/manifest.json").toFile());
    }

    @Test
    void manifestCoversEveryWholeProgramAndSetupFile() throws Exception {
        JsonNode catalog = manifest();
        assertEquals(1, catalog.required("schemaVersion").asInt());
        Set<String> variants = Set.of("java", "kotlin", "scala-direct", "scala-cats");
        Set<String> expected = new HashSet<>();
        for (String variant : variants) {
            for (String program : Set.of(
                    "Connect",
                    "ListSessions",
                    "ListWindows",
                    "ListPanes",
                    "NewSession",
                    "NewWindow",
                    "Query",
                    "Capture")) {
                expected.add(variant + "-" + program);
            }
        }
        Set<String> actual = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode program : programs().toList()) {
            assertTrue(actual.add(program.required("id").asText()), "duplicate example id");
            String path = program.required("sourceFile").asText();
            assertTrue(path.startsWith("examples/src/main/"), path);
            assertFalse(path.contains(".."), path);
            assertTrue(paths.add(path), "a whole program must have its own source file");
            assertTrue(Files.isRegularFile(ROOT.resolve(path)), path);
            Class.forName(program.required("mainClass").asText()).getMethod("main", String[].class);
            assertFalse(program.required("description").asText().isBlank());
            assertFalse(program.required("expectedOutput").asText().isBlank());
            assertFalse(program.required("targets").isEmpty());
            assertTrue(program.required("path").asText().startsWith("src/main/"));
        }
        assertEquals(expected, actual);
        Set<String> sources = new HashSet<>();
        for (String language : Set.of("java", "kotlin", "scala")) {
            Path sourceRoot = Path.of("src/main", language, "io/github/libtmux/examples/api");
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile).forEach(path -> sources.add("examples/" + path));
            }
        }
        assertEquals(sources, paths, "every program must be attached and executed");
        Set<String> describedVariants = new HashSet<>();
        catalog.required("variants").fieldNames().forEachRemaining(describedVariants::add);
        assertEquals(variants, describedVariants);
        for (JsonNode variant : catalog.required("variants")) {
            Set<String> destinations = new HashSet<>();
            for (JsonNode file : variant.required("files")) {
                assertTrue(Files.isRegularFile(
                        ROOT.resolve(file.required("sourceFile").asText())));
                assertTrue(destinations.add(file.required("path").asText()), "duplicate setup file");
            }
            assertEquals(
                    Set.of("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "run.sh"), destinations);
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void mainPrintsDocumentedOutput(JsonNode program) throws Exception {
        String output = MainsTest.launchApiProgram(program.required("mainClass").asText());
        assertEquals(program.required("expectedOutput").asText(), output);
    }
}
