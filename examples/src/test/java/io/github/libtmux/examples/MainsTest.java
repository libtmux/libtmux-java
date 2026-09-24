package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Every example's own {@code main}, in its own JVM, as a reader would run it.
 *
 * <p>{@link ExamplesRunTest} checks what each {@code run} returns. This checks what a reader sees:
 * the program starts from its class name, exits zero, and prints what it says it prints.
 */
@ExtendWith(TmuxExtension.class)
final class MainsTest {

    @Test
    void everyExampleIsLaunched() throws IOException {
        List<String> programs = Stream.concat(
                        names(Path.of("src/main/java/io/github/libtmux/examples")).stream(),
                        names(Path.of("src/main/kotlin/io/github/libtmux/examples")).stream())
                .sorted()
                .toList();

        assertEquals(
                List.of(
                        "BuildAWorkspace",
                        "FindPanesRunning",
                        "RunACommand",
                        "ServeTmuxOverMcp",
                        "WatchPaneOutput",
                        "WatchWhatChanges",
                        "WatchWithFlow"),
                programs,
                "a new example needs a launch below");
    }

    @Test
    void buildAWorkspace(Server server, TmuxSocketPath socket) throws Exception {
        String out = launch("BuildAWorkspace", socket.path().toString());

        assertTrue(out.startsWith("session work has "), out);
        assertTrue(server.hasSession("work"));
    }

    @Test
    void findPanesRunning(TmuxSocketPath socket) throws Exception {
        String out = launch("FindPanesRunning", socket.path().toString(), "no-such-command-anywhere");

        assertEquals("", out);
    }

    @Test
    void runACommand(TmuxSocketPath socket) throws Exception {
        String out = launch("RunACommand", socket.path().toString(), "printf 'built\\n'; exit 3");

        assertEquals("exit 3, 1 line(s): built\n", out);
    }

    @Test
    void serveTmuxOverMcp(TmuxSocketPath socket) throws Exception {
        List<String> tools =
                launch("ServeTmuxOverMcp", socket.path().toString()).lines().toList();

        assertTrue(tools.contains("capture_pane"), tools.toString());
    }

    @Test
    void watchPaneOutput(TmuxSocketPath socket) throws Exception {
        String out = launch("WatchPaneOutput", socket.path().toString());

        assertTrue(WatchPaneOutput.printedLine(out, "watched"), out);
    }

    @Test
    void watchWhatChanges(TmuxSocketPath socket) throws Exception {
        String out = launch("WatchWhatChanges", socket.path().toString());

        assertTrue(out.contains("watched-into-existence"), out);
    }

    @Test
    void watchWithFlow(TmuxSocketPath socket) throws Exception {
        String out = launch("WatchWithFlowKt", socket.path().toString());

        assertEquals("echoed=true\ncancelled=true\n", out);
    }

    private static List<String> names(Path sources) throws IOException {
        try (Stream<Path> files = Files.list(sources)) {
            return files.map(file -> file.getFileName().toString().replaceFirst("\\.(java|kt)$", ""))
                    .filter(name -> !name.equals("package-info"))
                    .toList();
        }
    }

    /** Runs the example's {@code main} in a fresh JVM and returns what it printed. */
    private static String launch(String program, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "io.github.libtmux.examples." + program));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), program + " did not exit");
        assertEquals(0, process.exitValue(), program + " printed:\n" + out);
        return out;
    }
}
