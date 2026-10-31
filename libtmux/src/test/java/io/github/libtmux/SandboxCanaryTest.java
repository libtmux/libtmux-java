package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client built the way a test that forgot its socket builds one still lands inside the build's
 * quarantine.
 *
 * <p>A recording tmux stands in for the real one, so what is asserted is what the library handed a
 * child process, not what a server did with it. The controls feed the same reader a client that was
 * given a pane's environment, so a reader that stopped looking would fail here.
 *
 * <p>The recording tmux writes argv and variable names, never a value, and no message here prints
 * one: an environment holds secrets, and a failure is copied into logs.
 */
final class SandboxCanaryTest {

    @TempDir
    Path directory;

    @Test
    void aBareClientReachesNoServerOutsideTheQuarantine() throws Exception {
        Path record = directory.resolve("record");
        Path tmux = recordingTmux(record, sandbox());

        try (Server server =
                Server.open(ServerConfig.builder().binary(tmux.toString()).build())) {
            server.cmd("list-sessions");
        }

        List<Call> calls = read(record);
        assertFalse(calls.isEmpty(), "the library ran no client, so nothing was observed");
        for (Call call : calls) {
            assertEquals(List.of(), leaks(call, sandbox()), "a client that named " + socketFlags(call.argv()));
        }
    }

    @Test
    void theQuarantineIsADirectoryUnderTheRootTheBuildNamed() {
        Path quarantine = Path.of(sandbox());
        Path root = Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")));

        assertTrue(Files.isDirectory(quarantine), "the build's quarantine is not a directory");
        assertTrue(
                quarantine.toString().equals(System.getenv("TMUX_TMPDIR")),
                "TMUX_TMPDIR is not the directory the build prepared");
        assertTrue(quarantine.startsWith(root), "the quarantine is outside java.io.tmpdir");
    }

    @Test
    void theReaderFlagsAClientThatInheritedAPanesEnvironment() throws Exception {
        Path record = directory.resolve("inside");
        Path tmux = recordingTmux(record, sandbox());

        run(
                tmux,
                Map.of(
                        "TMUX", "/tmp/default,1,0",
                        "TMUX_PANE", "%1",
                        "LIBTMUX_SOCKET", "work",
                        "LIBTMUX_SOCKET_PATH", "/tmp/work",
                        "TMUX_TMPDIR", "/tmp"),
                "list-sessions");

        List<String> found = leaks(read(record).getFirst(), sandbox());

        assertEquals(5, found.size(), found.toString());
        assertEquals("TMUX is set", found.getFirst());
    }

    @Test
    void theReaderFlagsASocketPathOutsideTheQuarantine() throws Exception {
        Path record = directory.resolve("elsewhere");
        Path tmux = recordingTmux(record, sandbox());

        run(tmux, Map.of("TMUX_TMPDIR", sandbox()), "-S", "/tmp/elsewhere/s", "list-sessions");

        assertEquals(
                List.of("-S names a socket outside the quarantine"),
                leaks(read(record).getFirst(), sandbox()));
    }

    @Test
    void theReaderFlagsAClientWhoseDefaultSocketIsNotTheQuarantines() throws Exception {
        Path record = directory.resolve("ambient");
        Path tmux = recordingTmux(record, sandbox());

        run(tmux, Map.of("TMUX_TMPDIR", "/tmp"), "list-sessions");

        assertEquals(
                List.of("TMUX_TMPDIR is not the quarantine"), leaks(read(record).getFirst(), sandbox()));
    }

    @Test
    void theReaderAcceptsTheSameClientInsideTheQuarantine() throws Exception {
        Path record = directory.resolve("inside-quarantine");
        Path tmux = recordingTmux(record, sandbox());

        run(tmux, Map.of("TMUX_TMPDIR", sandbox()), "-L", "named", "list-sessions");

        assertEquals(List.of(), leaks(read(record).getFirst(), sandbox()));
    }

    @Test
    void aRealBareClientStartsItsServerInsideTheQuarantine() throws Exception {
        String tmux = realTmux();
        Path socket = defaultSocketUnder(Path.of(sandbox()));
        try {
            startBareServer(tmux, Map.of());

            assertTrue(Files.exists(socket), "the bare client's server is not listening in the quarantine");
            assertEquals(0, stopServer(tmux, socket), "no server was listening in the quarantine to stop");
        } finally {
            stopServer(tmux, socket);
        }
    }

    @Test
    void aRealClientThatEscapedTheQuarantineIsNotSeenInsideIt() throws Exception {
        String tmux = realTmux();
        Path elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        Path inside = defaultSocketUnder(Path.of(sandbox()));
        Path outside = defaultSocketUnder(elsewhere);
        try {
            startBareServer(tmux, Map.of("TMUX_TMPDIR", elsewhere.toString()));

            assertFalse(Files.exists(inside), "the control's server appeared in the quarantine");
            assertTrue(Files.exists(outside), "the control's server is not where it was told to start");
            assertEquals(0, stopServer(tmux, outside), "no server was listening where the control started one");
        } finally {
            stopServer(tmux, outside);
        }
    }

    /** What the build prepared for this task; the same directory it exported as {@code TMUX_TMPDIR}. */
    private static String sandbox() {
        return Objects.requireNonNull(System.getProperty("libtmux.test.sandbox"), "no build task prepared this JVM");
    }

    private static String realTmux() throws Exception {
        String tmux = System.getProperty("libtmux.tmux", "tmux");
        boolean runs;
        try {
            Process version =
                    new ProcessBuilder(tmux, "-V").redirectErrorStream(true).start();
            version.getInputStream().readAllBytes();
            runs = version.waitFor(30, TimeUnit.SECONDS) && version.exitValue() == 0;
        } catch (IOException missing) {
            runs = false;
        }
        assumeTrue(runs, "no tmux to start");
        return tmux;
    }

    /** Where a bare client's server listens when its {@code TMUX_TMPDIR} is {@code tmpdir}. */
    private static Path defaultSocketUnder(Path tmpdir) throws IOException {
        Object owner = Files.getAttribute(Path.of(sandbox()), "unix:uid");
        return tmpdir.resolve("tmux-" + owner).resolve("default");
    }

    /**
     * Starts a server the way a test that forgot its socket does: no {@code -S} and no {@code -L}. The
     * client inherits this JVM's environment, with {@code environment} added to it.
     */
    private static void startBareServer(String tmux, Map<String, String> environment) throws Exception {
        ProcessBuilder client = new ProcessBuilder(tmux, "-f", "/dev/null", "new-session", "-d", "-s", "canary", "cat")
                .redirectErrorStream(true);
        client.environment().putAll(environment);
        Process started = client.start();
        started.getInputStream().readAllBytes();
        assertTrue(started.waitFor(30, TimeUnit.SECONDS), "the bare client did not exit");
        assertEquals(0, started.exitValue(), "the bare client could not start a server");
    }

    /** Stops the server on {@code socket} with {@code -S}, and returns the client's exit status. */
    private static int stopServer(String tmux, Path socket) throws Exception {
        Process stop = new ProcessBuilder(tmux, "-S", socket.toString(), "kill-server")
                .redirectErrorStream(true)
                .start();
        stop.getInputStream().readAllBytes();
        assertTrue(stop.waitFor(30, TimeUnit.SECONDS), "kill-server did not exit");
        return stop.exitValue();
    }

    /** Which socket flags a client carried; the flags' values are paths and names, and are not reported. */
    private static String socketFlags(List<String> argv) {
        boolean path = argv.contains("-S");
        boolean name = argv.contains("-L");
        return path && name ? "-S and -L" : path ? "-S" : name ? "-L" : "no socket flag";
    }

    private static List<String> leaks(Call call, String sandbox) {
        List<String> found = new ArrayList<>();
        call.selectors().forEach(name -> found.add(name + " is set"));
        int path = call.argv().indexOf("-S");
        if (path >= 0) {
            String socket = call.argv().get(path + 1);
            if (!Path.of(socket).startsWith(sandbox)) {
                found.add("-S names a socket outside the quarantine");
            }
        } else if (call.tmpdir().equals("unset")) {
            found.add("TMUX_TMPDIR is unset");
        } else if (!call.tmpdir().equals("sandbox")) {
            found.add("TMUX_TMPDIR is not the quarantine");
        }
        return found;
    }

    /**
     * One client the recording tmux was started as.
     *
     * @param selectors the names of the variables that choose a server and were set
     * @param tmpdir whether {@code TMUX_TMPDIR} was {@code unset}, the {@code sandbox} or {@code other}
     */
    private record Call(List<String> argv, List<String> selectors, String tmpdir) {}

    private static List<Call> read(Path record) throws IOException {
        List<Call> calls = new ArrayList<>();
        List<String> argv = new ArrayList<>();
        List<String> selectors = new ArrayList<>();
        String tmpdir = "unset";
        for (String line : Files.readAllLines(record)) {
            if (line.equals("END")) {
                calls.add(new Call(List.copyOf(argv), List.copyOf(selectors), tmpdir));
                argv.clear();
                selectors.clear();
                tmpdir = "unset";
            } else if (line.startsWith("ARG ")) {
                argv.add(line.substring(4));
            } else if (line.startsWith("SET ")) {
                selectors.add(line.substring(4));
            } else if (line.startsWith("TMPDIR ")) {
                tmpdir = line.substring(7);
            }
        }
        return calls;
    }

    /** Starts the recording tmux as a child whose environment is {@code PATH} and {@code extra}, whatever this JVM holds. */
    private static void run(Path tmux, Map<String, String> extra, String... argv) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(tmux.toString());
        command.addAll(List.of(argv));
        ProcessBuilder client = new ProcessBuilder(command).redirectErrorStream(true);
        String path = client.environment().get("PATH");
        client.environment().clear();
        if (path != null) {
            client.environment().put("PATH", path);
        }
        client.environment().putAll(extra);
        Process started = client.start();
        assertTrue(started.waitFor(30, TimeUnit.SECONDS), "the recording tmux did not exit");
    }

    /**
     * A tmux that appends its argv, the names of the variables that choose a server, and whether
     * {@code TMUX_TMPDIR} is {@code sandbox} to {@code record}, and answers the version probe.
     *
     * <p>It reads the environment only to test names, and writes no value. Everything else exits 0
     * silently, which a server with no sessions also does.
     */
    private static Path recordingTmux(Path record, String sandbox) throws IOException {
        Path script = record.resolveSibling(record.getFileName() + "-tmux");
        Files.writeString(script, """
                #!/bin/sh
                {
                  for argument in "$@"; do printf 'ARG %%s\\n' "$argument"; done
                  [ -n "${TMUX+x}" ] && printf 'SET TMUX\\n'
                  [ -n "${TMUX_PANE+x}" ] && printf 'SET TMUX_PANE\\n'
                  env | sed -n 's/^\\(LIBTMUX_SOCKET[A-Za-z0-9_]*\\)=.*/SET \\1/p' | sort
                  if [ -z "${TMUX_TMPDIR+x}" ]; then printf 'TMPDIR unset\\n'
                  elif [ "$TMUX_TMPDIR" = '%s' ]; then printf 'TMPDIR sandbox\\n'
                  else printf 'TMPDIR other\\n'; fi
                  printf 'END\\n'
                } >> '%s'
                for argument in "$@"; do
                  case "$argument" in
                    libtmux-version-*)
                      printf 'unknown command: %%s3.6a\\n' "${argument%%'#{version}'}" >&2
                      exit 1 ;;
                  esac
                done
                """.formatted(sandbox, record));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
