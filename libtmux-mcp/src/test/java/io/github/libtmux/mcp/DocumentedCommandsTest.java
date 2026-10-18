package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every {@code libtmux-mcp} command and client configuration the Markdown shows is one the launcher
 * accepts.
 *
 * <p>A documented flag or variable the launcher does not read fails for the reader who pastes it,
 * and a misspelled variable fails silently, because the launcher ignores an environment name it
 * does not know. The check hands each documented invocation to the code the launcher runs, so a
 * rename that leaves a README behind fails the build.
 */
final class DocumentedCommandsTest {

    private static final String PROGRAM = "libtmux-mcp";

    private static final Pattern ASSIGNMENT = Pattern.compile("^(LIBTMUX_[A-Z0-9_]+)=(.*)$");

    /** The {@code LIBTMUX_} names the launcher's sources spell out, which are the ones it can read. */
    private static Set<String> variablesRead() throws IOException {
        Path sources = MarkdownCommands.repository().resolve("libtmux-mcp/src/main/java");
        Pattern literal = Pattern.compile("\"(LIBTMUX_[A-Z0-9_]+)\"");
        Set<String> names = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(sources)) {
            for (Path file :
                    tree.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher found = literal.matcher(Files.readString(file));
                while (found.find()) {
                    names.add(found.group(1));
                }
            }
        }
        return names;
    }

    /**
     * Why the launcher would not start from this invocation, or empty when it would.
     *
     * @param arguments what follows {@code libtmux-mcp}
     * @param environment the {@code LIBTMUX_} variables the invocation sets
     * @param read the names the launcher reads
     */
    private static List<String> problemsWith(
            List<String> arguments, Map<String, String> environment, Set<String> read) {
        List<String> problems = new ArrayList<>();
        for (String name : environment.keySet()) {
            if (!read.contains(name)) {
                problems.add(name + " is not read by libtmux-mcp");
            }
        }
        // A configuration file the launcher would otherwise write to the temp directory.
        Map<String, String> effective = new HashMap<>(environment);
        effective.putIfAbsent(LaunchConfiguration.CONFIG_ENV, "/dev/null");
        try {
            ToolSurface.resolve(effective);
            LaunchConfiguration.resolve(arguments, effective);
        } catch (IllegalArgumentException refused) {
            problems.add(refused.getMessage());
        }
        return problems;
    }

    /** The {@code LIBTMUX_} assignments a command makes, whether it leads the command or follows {@code -e}. */
    private static Map<String, String> environmentOf(List<String> words) {
        Map<String, String> environment = new HashMap<>();
        for (String word : words) {
            Matcher assignment = ASSIGNMENT.matcher(word);
            if (assignment.matches()) {
                environment.put(assignment.group(1), assignment.group(2));
            }
        }
        return environment;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    /** The problems with each server a client configuration starts, keyed by what it shows. */
    private static List<String> configurationProblems(String json, Set<String> read) throws IOException {
        List<String> problems = new ArrayList<>();
        for (JsonNode server : new ObjectMapper().readTree(json).path("mcpServers")) {
            if (!server.path("command").asText().endsWith(PROGRAM)) {
                continue;
            }
            Map<String, String> environment = new HashMap<>();
            server.path("env")
                    .properties()
                    .forEach(entry ->
                            environment.put(entry.getKey(), entry.getValue().asText()));
            problems.addAll(problemsWith(strings(server.path("args")), environment, read));
        }
        return problems;
    }

    private static final Set<String> READ = Set.of("LIBTMUX_TOOLSETS", "LIBTMUX_TOOLS", "LIBTMUX_EXCLUDE_TOOLS");

    @Test
    void aFlagTheLauncherReadsIsNotAProblem() {
        assertEquals(
                List.of(), problemsWith(List.of("--socket-name", "work", "--tmux", "/usr/bin/tmux"), Map.of(), READ));
        assertEquals(
                List.of(),
                problemsWith(
                        List.of("--socket", "/tmp/libtmux-java-dev/probe/s"),
                        Map.of("LIBTMUX_TOOLSETS", "inspect"),
                        READ));
    }

    @Test
    void aMisspelledFlagIsAProblem() {
        assertFalse(
                problemsWith(List.of("--sokcet-name", "work"), Map.of(), READ).isEmpty());
    }

    @Test
    void aFlagWithoutItsValueIsAProblem() {
        assertFalse(problemsWith(List.of("--socket-name"), Map.of(), READ).isEmpty());
    }

    @Test
    void aMisspelledVariableIsAProblemEvenThoughTheLauncherWouldIgnoreIt() {
        assertEquals(
                List.of("LIBTMUX_TOOLSET is not read by libtmux-mcp"),
                problemsWith(List.of(), Map.of("LIBTMUX_TOOLSET", "inspect"), READ));
    }

    @Test
    void aRetiredVariableIsAProblem() {
        assertFalse(problemsWith(List.of(), Map.of("LIBTMUX_SAFETY", "readonly"), READ)
                .isEmpty());
    }

    @Test
    void aToolNobodyRegisteredIsAProblem() {
        assertFalse(problemsWith(List.of(), Map.of("LIBTMUX_TOOLS", "capture_pain"), READ)
                .isEmpty());
    }

    @Test
    void aVariableIsFoundWhetherItLeadsTheCommandOrFollowsDashE() {
        assertEquals(
                Map.of("LIBTMUX_TOOLSETS", "inspect"),
                environmentOf(MarkdownCommands.words("LIBTMUX_TOOLSETS=inspect libtmux-mcp --socket-name work")));
        assertEquals(
                Map.of("LIBTMUX_TOOLSETS", "inspect"),
                environmentOf(MarkdownCommands.words(
                        "claude mcp add tmux -e LIBTMUX_TOOLSETS=inspect -- libtmux-mcp --socket-name work")));
    }

    @Test
    void aClientConfigurationIsHeldToTheSameRules() throws IOException {
        String good = """
                {"mcpServers": {"tmux": {"command": "/x/libtmux-mcp",
                  "args": ["--socket-name", "work"], "env": {"LIBTMUX_TOOLSETS": "inspect"}}}}
                """;
        String badFlag = good.replace("--socket-name", "--sokcet-name");
        String badVariable = good.replace("LIBTMUX_TOOLSETS", "LIBTMUX_TOOLSET");

        assertEquals(List.of(), configurationProblems(good, READ));
        assertFalse(configurationProblems(badFlag, READ).isEmpty());
        assertFalse(configurationProblems(badVariable, READ).isEmpty());
    }

    /** The documents are read, and every launcher invocation in them is one the launcher accepts. */
    @Test
    void everyDocumentedInvocationIsOneTheLauncherAccepts() throws IOException {
        Path root = MarkdownCommands.repository();
        Set<String> read = variablesRead();
        assertTrue(read.containsAll(Set.of("LIBTMUX_TOOLSETS", "LIBTMUX_SOCKET")), "read " + read);
        int checked = 0;
        List<String> problems = new ArrayList<>();
        for (Path document : MarkdownCommands.readerFacing(root)) {
            String markdown = Files.readString(document);
            for (List<String> words : MarkdownCommands.consoleCommands(markdown)) {
                if (!MarkdownCommands.runs(words, PROGRAM)) {
                    continue;
                }
                checked++;
                for (String problem :
                        problemsWith(MarkdownCommands.argumentsOf(words, PROGRAM), environmentOf(words), read)) {
                    problems.add(root.relativize(document) + " shows `" + String.join(" ", words) + "`: " + problem);
                }
            }
            for (String json : MarkdownCommands.jsonFences(markdown)) {
                if (json.contains("\"mcpServers\"")) {
                    checked++;
                    for (String problem : configurationProblems(json, read)) {
                        problems.add(root.relativize(document) + " configures libtmux-mcp: " + problem);
                    }
                }
            }
        }

        assertEquals(List.of(), problems);
        assertTrue(checked >= 8, "checked " + checked + " documented invocations; the scan is broken");
    }
}
