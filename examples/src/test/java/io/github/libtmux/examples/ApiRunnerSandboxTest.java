package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code api/run.sh} hands the program it starts, and what it does when the program leaves a server.
 * The program reports names and booleans, never a value.
 */
final class ApiRunnerSandboxTest {

    private static final String TMUX = System.getProperty("libtmux.tmux", "tmux");

    @TempDir
    Path directory;

    private final String program = """
            {
              [ -n "${TMUX+x}" ] && echo 'SET TMUX'
              [ -n "${TMUX_PANE+x}" ] && echo 'SET TMUX_PANE'
              env | sed -n 's/^\\(LIBTMUX_SOCKET[A-Za-z0-9_]*\\)=.*/SET \\1/p' | sort
              if [ -z "${TMUX_TMPDIR+x}" ]; then echo 'TMPDIR unset'
              elif [ "$TMUX_TMPDIR" = "$DECOY" ]; then echo 'TMPDIR decoy'
              else
                case "$TMUX_TMPDIR" in "$ROOT"/*) echo 'TMPDIR under-root' ;; *) echo 'TMPDIR other' ;; esac
              fi
              ls -ld "$TMUX_TMPDIR" | cut -c1-10 | sed 's/^/MODE /'
            } > "$OUT"
            "$1" -S "$2" display-message -p '#{pid}' > "$OWNED_PID"
            serve() {
              "$1" -S "$2" -f /dev/null new-session -d -s leak cat
              "$1" -S "$2" display-message -p '#{pid}' > "$LEAK_PID"
            }
            case "${CHILD:-}" in
              leak)
                "$1" -f /dev/null new-session -d -s leak cat
                "$1" display-message -p '#{pid}' > "$LEAK_PID" ;;
              leak-custom) serve "$1" "$TMUX_TMPDIR/custom.sock" ;;
              leak-nested) mkdir "$TMUX_TMPDIR/sub"; serve "$1" "$TMUX_TMPDIR/sub/x.sock" ;;
              stale)
                serve "$1" "$TMUX_TMPDIR/stale.sock"
                kill -9 "$(cat "$LEAK_PID")"
                attempts=0
                while kill -0 "$(cat "$LEAK_PID")" 2>/dev/null && [ "$attempts" -lt 100 ]; do
                  sleep 0.05; attempts=$((attempts + 1))
                done
                [ -S "$TMUX_TMPDIR/stale.sock" ] && echo yes > "$STALE_REMAINS" ;;
              link) ln -s "$OUTSIDE" "$TMUX_TMPDIR/link.sock" ;;
              wait) sleep 30 ;;
            esac
            """;

    private static final AtomicInteger ROOTS = new AtomicInteger();

    // The tmux the launcher is told to use; a test swaps in a wrapper to make kill-server do nothing.
    private String tmuxBin = TMUX;

    private Path outside() {
        return directory.resolve("outside.sock");
    }

    // Short, because the runner refuses a root whose default socket path would pass 100 bytes.
    private final Path root = Path.of(System.getProperty("java.io.tmpdir")).resolve("r" + ROOTS.incrementAndGet());

    private Path root() {
        return root;
    }

    private record Result(int status, String output) {}

    /** Runs the launcher with a pane's environment, and the program, in the mode the program reads. */
    private Process start(String child, Path root) throws IOException {
        return start(child, root, true);
    }

    private Process start(String child, Path root, boolean callerHasTmuxTmpdir) throws IOException {
        Path script = Files.writeString(directory.resolve("program.sh"), program);
        ProcessBuilder launcher =
                new ProcessBuilder("sh", "api/run.sh", "sh", script.toString()).redirectErrorStream(true);
        Map<String, String> environment = launcher.environment();
        environment.putAll(Map.of(
                "TMUX",
                directory + "/decoy,1,0",
                "TMUX_PANE",
                "%3",
                "LIBTMUX_SOCKET",
                "decoy",
                "LIBTMUX_SOCKET_PATH",
                directory + "/decoy.sock",
                "TMUX_TMPDIR",
                Files.createDirectories(directory.resolve("decoy-tmpdir")).toString()));
        if (!callerHasTmuxTmpdir) {
            environment.remove("TMUX_TMPDIR");
        }
        environment.putAll(Map.of(
                "TMUX_BIN", tmuxBin,
                "API_RUN_ROOT", root.toString(),
                "DECOY", directory.resolve("decoy-tmpdir").toString(),
                "ROOT", root.toString(),
                "OUT", directory.resolve("out").toString(),
                "OWNED_PID", directory.resolve("owned.pid").toString(),
                "LEAK_PID", directory.resolve("leak.pid").toString(),
                "CHILD", child,
                "OUTSIDE", outside().toString(),
                "STALE_REMAINS", directory.resolve("stale-remains").toString()));
        return launcher.start();
    }

    private Result run(String child, Path root) throws Exception {
        return run(child, root, true);
    }

    private Result run(String child, Path root, boolean callerHasTmuxTmpdir) throws Exception {
        Process launcher = start(child, root, callerHasTmuxTmpdir);
        String output = new String(launcher.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(launcher.waitFor(60, TimeUnit.SECONDS), "the launcher did not exit");
        return new Result(launcher.exitValue(), output);
    }

    private List<String> report() throws IOException {
        return Files.readAllLines(directory.resolve("out"));
    }

    private String realTmux() {
        if (TMUX.contains("/")) return TMUX;
        for (String entry : System.getenv("PATH").split(":", -1)) {
            Path candidate = Path.of(entry, TMUX);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        throw new AssertionError("no tmux on PATH");
    }

    /** Runs the real tmux on its own and returns its exit status; used only on sockets this test owns. */
    private int runTmux(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(realTmux()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "tmux did not exit");
        return process.exitValue();
    }

    private boolean rootIsEmpty() throws IOException {
        try (Stream<Path> left = Files.list(root())) {
            return left.findAny().isEmpty();
        }
    }

    private boolean alive(String pidFile) throws IOException {
        Path file = directory.resolve(pidFile);
        return Files.isRegularFile(file)
                && ProcessHandle.of(Long.parseLong(Files.readString(file).trim()))
                        .map(ProcessHandle::isAlive)
                        .orElse(false);
    }

    // A server this test started, stopped by its pid so a failing case leaves nothing behind.
    private void stop(String pidFile) throws IOException {
        Path file = directory.resolve(pidFile);
        if (Files.isRegularFile(file)) {
            ProcessHandle.of(Long.parseLong(Files.readString(file).trim())).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private boolean awaitStopped(String pidFile) throws Exception {
        for (int attempt = 0; attempt < 100 && alive(pidFile); attempt++) {
            Thread.sleep(50);
        }
        return !alive(pidFile);
    }

    @Test
    void theProgramGetsNoSelectorAndAPrivateTmuxTmpdirOfItsOwn() throws Exception {
        try {
            Result ran = run("", root());

            assertEquals(0, ran.status(), "the launcher failed");
            assertEquals(
                    List.of("TMPDIR under-root", "MODE drwx------"),
                    report(),
                    "the program saw a selector, the decoy TMUX_TMPDIR, or a directory open to others");
            assertTrue(awaitStopped("owned.pid"), "the launcher's own server was left running");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            stop("owned.pid");
        }
    }

    @Test
    void theProgramGetsATmuxTmpdirEvenWhenTheCallerHadNone() throws Exception {
        try {
            Result ran = run("", root(), false);

            assertEquals(0, ran.status(), "the launcher failed");
            assertEquals(
                    List.of("TMPDIR under-root", "MODE drwx------"),
                    report(),
                    "the program saw a selector, or no private TMUX_TMPDIR at all");
        } finally {
            stop("owned.pid");
        }
    }

    @Test
    void aServerTheProgramLeavesFailsTheRunAndIsStopped() throws Exception {
        try {
            Result ran = run("leak", root());

            assertTrue(ran.status() != 0, "the launcher passed with a server left running");
            assertTrue(
                    Pattern.compile("The program left a tmux server running: tmux-\\d+/default")
                            .matcher(ran.output())
                            .find(),
                    "the launcher did not name the socket");
            assertTrue(awaitStopped("leak.pid"), "the server the program left was not stopped");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            stop("leak.pid");
            stop("owned.pid");
        }
    }

    @Test
    void aServerOnASocketTheProgramNamedInsideTheQuarantineFailsTheRunAndIsStopped() throws Exception {
        try {
            Result ran = run("leak-custom", root());

            assertTrue(ran.status() != 0, "the launcher passed with a server left running");
            assertTrue(
                    ran.output().contains("The program left a tmux server running: custom.sock"),
                    "the launcher did not name the socket");
            assertTrue(awaitStopped("leak.pid"), "the server the program left was not stopped");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            stop("leak.pid");
            stop("owned.pid");
        }
    }

    @Test
    void aServerOnASocketInASubdirectoryIsFoundToo() throws Exception {
        try {
            Result ran = run("leak-nested", root());

            assertTrue(ran.status() != 0, "the launcher passed with a server left running");
            assertTrue(
                    ran.output().contains("The program left a tmux server running: sub/x.sock"),
                    "the launcher did not name the socket");
            assertTrue(awaitStopped("leak.pid"), "the server the program left was not stopped");
        } finally {
            stop("leak.pid");
            stop("owned.pid");
        }
    }

    @Test
    void aSocketFileNothingAnswersOnIsNotALeak() throws Exception {
        try {
            Result ran = run("stale", root());

            assertTrue(Files.exists(directory.resolve("stale-remains")), "no stale socket was left");
            assertEquals(0, ran.status(), "the launcher failed");
            assertFalse(ran.output().contains("left a tmux server"), "a socket nothing answers on was reported");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            stop("leak.pid");
            stop("owned.pid");
        }
    }

    @Test
    void aLinkToASocketOutsideTheQuarantineIsNeitherFollowedNorStopped() throws Exception {
        runTmux("-S", outside().toString(), "-f", "/dev/null", "new-session", "-d", "-s", "outside", "cat");
        try {
            Result ran = run("link", root());

            assertEquals(0, ran.status(), "the launcher failed");
            assertFalse(ran.output().contains("left a tmux server"), "a link was followed to a server it does not own");
            assertEquals(
                    0,
                    runTmux("-S", outside().toString(), "display-message", "-p", "ok"),
                    "the outside server was stopped");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            runTmux("-S", outside().toString(), "kill-server");
            stop("owned.pid");
        }
    }

    @Test
    void theDirectoriesAreKeptAndTheRunFailsWhenKillServerDoesNotStopTheServer() throws Exception {
        Path noop = Files.writeString(
                directory.resolve("tmux-noop"),
                "#!/bin/sh\nfor a in \"$@\"; do [ \"$a\" = kill-server ] && exit 0; done\nexec " + realTmux()
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(noop, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        tmuxBin = noop.toString();
        try {
            Result ran = run("leak-custom", root());

            assertTrue(ran.status() != 0, "the launcher passed with a server it could not stop");
            assertTrue(ran.output().contains("still answers on"), "the launcher did not say a server still answers");
            assertTrue(ran.output().contains("custom.sock"), "the launcher did not name the socket");
            assertTrue(alive("leak.pid"), "the control never left a server running");
            assertFalse(rootIsEmpty(), "the launcher removed a directory a server still answers on");
        } finally {
            stop("leak.pid");
            stop("owned.pid");
            try (Stream<Path> left = Files.walk(root())) {
                left.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aRootThatIsTooLongIsRefusedBeforeTheProgramRuns() throws Exception {
        Path tooLong = directory.resolve("x".repeat(90));

        Result ran = run("", tooLong);

        assertTrue(ran.status() != 0, "the launcher accepted a root too long for a socket");
        assertTrue(ran.output().contains("API_RUN_ROOT is too long"), "the launcher did not say why");
        assertFalse(Files.exists(directory.resolve("out")), "the program ran anyway");
        try (Stream<Path> left = Files.list(tooLong)) {
            assertTrue(left.findAny().isEmpty(), "the launcher left its directories behind");
        }
    }

    @Test
    void aTerminatedRunStopsItsServersAndRemovesItsDirectories() throws Exception {
        Process launcher = start("wait", root());
        try {
            for (int attempt = 0; attempt < 600 && !Files.exists(directory.resolve("owned.pid")); attempt++) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(directory.resolve("owned.pid")), "the program never started");
            // A terminal's interrupt reaches the whole foreground group: the program and the launcher.
            List<ProcessHandle> group = launcher.descendants().toList();
            launcher.destroy();
            group.forEach(ProcessHandle::destroy);

            assertTrue(launcher.waitFor(60, TimeUnit.SECONDS), "the launcher did not exit when terminated");
            assertTrue(launcher.exitValue() != 0, "a terminated launcher reported success");
            assertTrue(awaitStopped("owned.pid"), "the launcher's own server was left running");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            launcher.descendants().forEach(ProcessHandle::destroyForcibly);
            launcher.destroyForcibly();
            stop("owned.pid");
        }
    }
}
