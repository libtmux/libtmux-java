package io.github.libtmux.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code api/run.sh} hands the program it starts, and what it does when the program leaves a server.
 *
 * <p>The program is a shell script that reports on its own environment, and the runner starts from the
 * environment of a pane, so each case fails on a runner that stopped scrubbing, giving a private
 * {@code TMUX_TMPDIR}, refusing a long root, or cleaning up. The program reports names and booleans,
 * never a value: an environment holds secrets, and a failure is copied into logs.
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
            if [ "${CHILD:-}" = leak ]; then
              "$1" -f /dev/null new-session -d -s leak cat
              "$1" display-message -p '#{pid}' > "$LEAK_PID"
            fi
            if [ "${CHILD:-}" = wait ]; then sleep 30; fi
            """;

    private Path root() {
        return directory.resolve("r");
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
                "TMUX_BIN", TMUX,
                "API_RUN_ROOT", root.toString(),
                "DECOY", directory.resolve("decoy-tmpdir").toString(),
                "ROOT", root.toString(),
                "OUT", directory.resolve("out").toString(),
                "OWNED_PID", directory.resolve("owned.pid").toString(),
                "LEAK_PID", directory.resolve("leak.pid").toString(),
                "CHILD", child));
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

            assertEquals(0, ran.status(), "the launcher failed: " + ran.output());
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

            assertEquals(0, ran.status(), "the launcher failed: " + ran.output());
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
                    ran.output().contains("The program left a tmux server running: default"),
                    "the launcher did not name the socket");
            assertTrue(awaitStopped("leak.pid"), "the server the program left was not stopped");
            assertTrue(rootIsEmpty(), "the launcher left its directories behind");
        } finally {
            stop("leak.pid");
            stop("owned.pid");
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
