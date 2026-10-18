package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Every {@code tmux-workspace} command the Markdown shows is one the parser accepts.
 *
 * <p>A documented command that names a subcommand or a flag this program lacks fails for the reader
 * who pastes it, and nothing else reads it. The check parses each command with the same command
 * line the program runs, so a rename that leaves a README behind fails the build.
 */
final class DocumentedCommandsTest {

    private static final String PROGRAM = "tmux-workspace";

    /** Why {@code arguments} is not a command {@code tmux-workspace} accepts, or empty when it is. */
    private static List<String> problemsWith(List<String> arguments) {
        try {
            Arguments.create().parseArgs(arguments.toArray(String[]::new));
            return List.of();
        } catch (CommandLine.ParameterException refused) {
            return List.of(refused.getMessage());
        }
    }

    @Test
    void aCommandContinuedOverLinesIsOneCommand() {
        String markdown = """
                ```console
                $ tmux-workspace load ./workspace.yaml \\
                    -S /tmp/libtmux-java-dev/workspace-example.sock \\
                    -d \\
                    --ndjson
                $ tmux-workspace ls
                ```
                """;

        List<List<String>> commands = MarkdownCommands.consoleCommands(markdown);

        assertEquals(
                List.of(
                        List.of(
                                "tmux-workspace",
                                "load",
                                "./workspace.yaml",
                                "-S",
                                "/tmp/libtmux-java-dev/workspace-example.sock",
                                "-d",
                                "--ndjson"),
                        List.of("tmux-workspace", "ls")),
                commands);
    }

    @Test
    void onlyAConsoleFenceHoldsCommands() {
        String markdown = """
                ```text
                $ tmux-workspace nonsense
                ```

                ```console
                $ ./gradlew :libtmux-workspace-cli:installDist
                ```
                """;

        List<List<String>> commands = MarkdownCommands.consoleCommands(markdown);

        assertEquals(1, commands.size());
        assertFalse(MarkdownCommands.runs(commands.get(0), PROGRAM));
    }

    @Test
    void anArgumentAfterAShellOperatorIsNotTheProgramsOwn() {
        List<String> words = MarkdownCommands.words("tmux-workspace ls --json | jq '.records'");

        assertEquals(List.of("ls", "--json"), MarkdownCommands.argumentsOf(words, PROGRAM));
    }

    @Test
    void theParserAcceptsWhatTheReadmeShows() {
        assertEquals(List.of(), problemsWith(List.of("load", "./workspace.yaml", "-S", "/tmp/s", "-d", "--ndjson")));
        assertEquals(List.of(), problemsWith(List.of("--generate", "bash")));
    }

    @Test
    void aMisspelledSubcommandIsRefused() {
        assertFalse(problemsWith(List.of("lod", "./workspace.yaml")).isEmpty());
    }

    @Test
    void aFlagTheCommandDoesNotDeclareIsRefused() {
        assertFalse(
                problemsWith(List.of("load", "./workspace.yaml", "--detach")).isEmpty());
        assertFalse(problemsWith(List.of("ls", "--json", "--recursive")).isEmpty());
    }

    @Test
    void aRequiredArgumentLeftOutIsRefused() {
        assertFalse(problemsWith(List.of("load", "-d")).isEmpty());
    }

    /** The documents are read, and every command in them is one the parser accepts. */
    @Test
    void everyDocumentedCommandIsOneTheParserAccepts() throws IOException {
        Path root = MarkdownCommands.repository();
        int checked = 0;
        List<String> problems = new ArrayList<>();
        for (Path document : MarkdownCommands.readerFacing(root)) {
            for (List<String> words : MarkdownCommands.consoleCommands(Files.readString(document))) {
                if (!MarkdownCommands.runs(words, PROGRAM)) {
                    continue;
                }
                checked++;
                for (String problem : problemsWith(MarkdownCommands.argumentsOf(words, PROGRAM))) {
                    problems.add(root.relativize(document) + " shows `" + String.join(" ", words) + "`: " + problem);
                }
            }
        }

        assertEquals(List.of(), problems);
        assertTrue(checked >= 8, "checked " + checked + " documented commands; the scan is broken");
    }
}
