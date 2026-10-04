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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Every example's own {@code main}, in its own JVM, as a reader would run it.
 *
 * <p>{@link ExamplesRunTest} checks what each {@code run} returns. This checks
 * what a reader sees: the program starts from its class name, exits zero, and
 * prints what it says it prints.
 */
@ExtendWith(TmuxExtension.class)
final class MainsTest {
    private static final String PACKAGE = "io.github.libtmux.examples";
    private static final String PACKAGE_DIR = PACKAGE.replace('.', '/');
    private static final String HANGS = "MainsTest$Hangs";
    private static final String FIXTURE_DIR = "/tmp/libtmux-java-dev/api.";

    @Test
    void everyExampleIsLaunched() throws IOException {
        List<String> programs = new ArrayList<>();
        for (String language : List.of("java", "kotlin")) {
            programs.addAll(names(Path.of("src/main", language, PACKAGE_DIR)));
        }
        programs.sort(null);

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
    void buildAWorkspace(Server tmux, TmuxSocketPath socket) throws Exception {
        String path = socket.path().toString();
        String out = launch("BuildAWorkspace", path);

        assertTrue(out.startsWith("session work has "), out);
        assertTrue(tmux.hasSession("work"));
    }

    @Test
    void findPanesRunning(TmuxSocketPath socket) throws Exception {
        String path = socket.path().toString();
        String out = launch("FindPanesRunning", path, "no-such-command");

        assertEquals("", out);
    }

    @Test
    void runACommand(TmuxSocketPath socket) throws Exception {
        String path = socket.path().toString();
        String out = launch("RunACommand", path, "printf 'built\\n'; exit 3");

        assertEquals("exit 3, 1 line(s): built\n", out);
    }

    @Test
    void serveTmuxOverMcp(TmuxSocketPath socket) throws Exception {
        String out = launch("ServeTmuxOverMcp", socket.path().toString());
        List<String> tools = out.lines().toList();

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
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.replaceFirst("\\.(java|kt)$", ""))
                    .filter(name -> !name.equals("package-info"))
                    .toList();
        }
    }

    @Test
    void aProgramStillRunningAtItsDeadlineFails() {
        Duration deadline = Duration.ofMillis(200);
        String message = failureOf(() -> hangsWithin(deadline));

        assertTrue(message.contains("did not exit"), message);
    }

    @Test
    void aTimedOutApiProgramStillCleansUpItsServer() {
        Duration deadline = Duration.ofSeconds(1);
        String message = failureOf(() -> hangsInFixture(deadline));

        assertTrue(message.contains("did not exit"), message);
        var lines = message.lines().filter(MainsTest::isFixtureSocket);
        Path socket = Path.of(lines.findFirst().orElseThrow());
        assertFalse(Files.exists(socket.getParent()), "fixture removed");
    }

    private static String failureOf(Executable run) {
        AssertionError failure = assertThrows(AssertionError.class, run);
        return String.valueOf(failure.getMessage());
    }

    private static boolean isFixtureSocket(String line) {
        return line.startsWith(FIXTURE_DIR) && line.endsWith("/tmux.sock");
    }

    private static void hangsWithin(Duration deadline) {
        Duration limit = Duration.ofSeconds(5);
        assertTimeoutPreemptively(limit, () -> run(deadline, plain(HANGS)));
    }

    private static void hangsInFixture(Duration deadline) {
        Duration limit = Duration.ofSeconds(10);
        assertTimeoutPreemptively(limit, () -> api(deadline, HANGS));
    }

    /** Never exits, and never closes its output. */
    static final class Hangs {
        public static void main(String[] args) throws InterruptedException {
            if (args.length == 3) System.out.println(args[1]);
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /**
     * One JVM start. {@code wrap} is the fixture that runs it, or empty to run
     * it directly with its error stream merged into its output.
     */
    private record Launch(List<String> wrap, String name, List<String> args) {
        List<String> command() {
            List<String> command = new ArrayList<>(wrap);
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            String classpath = System.getProperty("java.class.path");
            command.addAll(List.of(java.toString(), "-cp", classpath));
            command.add(qualified());
            command.addAll(args);
            return command;
        }

        String qualified() {
            return name.contains(".") ? name : PACKAGE + "." + name;
        }
    }

    private static String launch(String name, String... args) throws Exception {
        return run(Duration.ofSeconds(60), plain(name, args));
    }

    static String launchApiProgram(String name) throws Exception {
        return api(Duration.ofSeconds(60), name);
    }

    private static String api(Duration limit, String name) throws Exception {
        String tmux = System.getProperty("libtmux.tmux", "tmux");
        String bin = "TMUX_BIN=" + tmux;
        List<String> wrap = List.of("env", bin, "sh", "api/run.sh");
        return run(limit, new Launch(wrap, name, List.of()));
    }

    /** The example's {@code main} in a fresh JVM, with no fixture around it. */
    private static Launch plain(String name, String... args) {
        return new Launch(List.of(), name, List.of(args));
    }

    private static String run(Duration limit, Launch launch) throws Exception {
        boolean direct = launch.wrap().isEmpty();
        var builder = new ProcessBuilder(launch.command());
        builder.redirectErrorStream(direct);
        if (!direct) builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        process.getOutputStream().close();
        // Drained apart from the wait: a program that never exits never
        // closes its output either.
        InputStream stdout = process.getInputStream();
        FutureTask<byte[]> printed = new FutureTask<>(stdout::readAllBytes);
        Thread.ofVirtual().start(printed);
        String program = launch.name();
        if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            stop(process, direct);
            String why = program + " did not exit within " + limit + ":\n";
            fail(why + text(printed));
        }
        String out = text(printed);
        assertEquals(0, process.exitValue(), program + " printed:\n" + out);
        return out;
    }

    private static void stop(Process process, boolean direct) throws Exception {
        process.descendants().forEach(ProcessHandle::destroy);
        if (direct) process.destroy();
        if (process.waitFor(5, TimeUnit.SECONDS)) return;
        process.destroy();
        if (process.waitFor(1, TimeUnit.SECONDS)) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly().waitFor();
    }

    private static String text(FutureTask<byte[]> printed) throws Exception {
        byte[] bytes = printed.get(10, TimeUnit.SECONDS);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
