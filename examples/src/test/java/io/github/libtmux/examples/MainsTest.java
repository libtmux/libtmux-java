package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.libtmux.Server;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import io.github.libtmux.testsupport.HangGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
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
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString().replaceFirst("\\.(java|kt)$", ""))
                    .filter(name -> !name.equals("package-info"))
                    .toList();
        }
    }

    @Test
    void aProgramStillRunningAtItsDeadlineFails() {
        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> assertTimeoutPreemptively(
                        HangGuard.DURATION, () -> launch(Duration.ofMillis(200), "MainsTest$Hangs")));

        assertTrue(String.valueOf(failure.getMessage()).contains("did not exit"), failure.getMessage());
    }

    @Test
    void aTimedOutApiProgramStillCleansUpItsServer() {
        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> assertTimeoutPreemptively(
                        HangGuard.DURATION, () -> launchApiProgram(Duration.ofSeconds(1), "MainsTest$Hangs")));
        String output = String.valueOf(failure.getMessage());
        assertTrue(output.contains("did not exit"), output);
        String socket = output.lines()
                .filter(line -> line.startsWith("/tmp/libtmux-java-dev/api.") && line.endsWith("/tmux.sock"))
                .findFirst()
                .orElseThrow();
        assertFalse(Files.exists(Path.of(socket).getParent()), "the timed-out fixture must be removed");
    }

    /** Never exits, and never closes its output. */
    static final class Hangs {
        public static void main(String[] args) throws InterruptedException {
            if (args.length == 3) System.out.println(args[1]);
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    private static String launch(String program, String... args) throws Exception {
        return launch(Duration.ofSeconds(60), program, args);
    }

    static String launchApiProgram(String program) throws Exception {
        return launchApiProgram(Duration.ofSeconds(60), program);
    }

    private static String launchApiProgram(Duration deadline, String program) throws Exception {
        List<String> fixture =
                List.of("env", "TMUX_BIN=" + System.getProperty("libtmux.tmux", "tmux"), "sh", "api/run.sh");
        return launch(deadline, false, true, fixture, program);
    }

    /** Runs the example's {@code main} in a fresh JVM and returns what it printed. */
    private static String launch(Duration deadline, String program, String... args) throws Exception {
        return launch(deadline, true, false, List.of(), program, args);
    }

    /**
     * With {@code clockStartsAtFirstOutput} the deadline bounds how long the program runs once it has
     * started, not how long tmux and the JVM take to come up: the API fixture prints the socket path
     * from the program's own first line.
     */
    private static String launch(
            Duration deadline,
            boolean combineError,
            boolean clockStartsAtFirstOutput,
            List<String> prefix,
            String program,
            String... args)
            throws Exception {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                program.startsWith("io.github.") ? program : "io.github.libtmux.examples." + program));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(combineError);
        if (!combineError) builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        process.getOutputStream().close();
        // Drained apart from the wait: a program that never exits never closes its output either.
        CountDownLatch firstOutput = new CountDownLatch(1);
        FutureTask<byte[]> printed = new FutureTask<>(() -> {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int read = stream.read(buffer); read >= 0; read = stream.read(buffer)) {
                    collected.write(buffer, 0, read);
                    firstOutput.countDown();
                }
            } finally {
                firstOutput.countDown();
            }
            return collected.toByteArray();
        });
        Thread.ofVirtual().start(printed);
        if (clockStartsAtFirstOutput && !firstOutput.await(HangGuard.SECONDS, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor();
            fail(program + " printed nothing within " + HangGuard.DURATION);
        }
        if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroy);
            if (prefix.isEmpty()) process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly().waitFor();
                }
            }
            fail(program + " did not exit within " + deadline + ", and printed:\n" + text(printed));
        }
        String out = text(printed);
        assertEquals(0, process.exitValue(), program + " printed:\n" + out);
        return out;
    }

    private static String text(FutureTask<byte[]> printed) throws Exception {
        return new String(printed.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    }
}
