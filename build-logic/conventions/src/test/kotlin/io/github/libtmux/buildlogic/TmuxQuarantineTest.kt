package io.github.libtmux.buildlogic

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What a task hands its child under libtmux.tmux-quarantine; the child reports names and booleans, never values. */
class TmuxQuarantineTest {

    private val socketRoot: Path = Path.of("/tmp/libtmux-java-test").resolve("tq" + ProcessHandle.current().pid())

    // The decoys sit under the test's own root, so a scrub that failed would start a server there and
    // nowhere else.
    private val inAPane =
        mapOf(
            "TMUX" to "$socketRoot/ambient,1,0",
            "TMUX_PANE" to "%7",
            "LIBTMUX_SOCKET" to "ambient",
            "LIBTMUX_SOCKET_PATH" to "$socketRoot/ambient.sock",
            "TMUX_TMPDIR" to "$socketRoot/ambient-tmpdir",
            "LIBTMUX_TOOLSETS" to "inspect",
        )

    @AfterEach
    fun removeSocketRoot() {
        socketRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a JavaExec task that opts in has no selector and a private TMUX_TMPDIR of its own`(@TempDir project: Path) {
        writeProbe(project, applyPlugin = true, optIn = true)

        assertQuarantined(run(project).report)
    }

    @Test
    fun `a JavaExec task that does not opt in keeps the developer's environment`(@TempDir project: Path) {
        writeProbe(project, applyPlugin = true, optIn = false)

        val seen = run(project).report

        for (name in inAPane.keys) {
            assertEquals("true", seen.getValue(name).getValue("unchanged"), "$name did not reach the child unchanged")
        }
    }

    @Test
    fun `the quarantine is stored by the configuration cache and still applies when it is reused`(
        @TempDir project: Path,
    ) {
        writeProbe(project, applyPlugin = true, optIn = true)

        val stored = run(project, "--configuration-cache")
        val reused = run(project, "--configuration-cache")

        assertTrue("Configuration cache entry stored" in stored.text, "the first run did not store an entry")
        assertTrue("Reusing configuration cache" in reused.text, "the second run did not reuse it")
        assertTrue("problem" !in reused.text, "the configuration cache reported a problem")
        assertQuarantined(stored.report)
        assertQuarantined(reused.report)
    }

    @Test
    fun `a quarantined task that leaves a server running fails the build and the server is stopped`(
        @TempDir project: Path,
    ) {
        requireTmux()
        writeProbe(project, applyPlugin = true, optIn = true, leak = "leak")
        val pidFile = project.resolve("server.pid")

        try {
            val failure = runAndFail(project)

            assertTrue(
                Regex("left a tmux server running: tmux-\\d+/default").containsMatchIn(failure),
                "the build did not name the socket",
            )
            assertTrue(!serverAlive(pidFile), "the server was left running")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `the check still runs when the task itself fails`(@TempDir project: Path) {
        requireTmux()
        writeProbe(project, applyPlugin = true, optIn = true, leak = "leak-and-fail")
        val pidFile = project.resolve("server.pid")

        try {
            val failure = runAndFail(project)

            assertTrue(
                Regex("left a tmux server running: tmux-\\d+/default").containsMatchIn(failure),
                "the build did not name the socket",
            )
            assertTrue(!serverAlive(pidFile), "the server was left running")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `a server on a socket the program named inside the quarantine fails the build and is stopped`(
        @TempDir project: Path,
    ) {
        requireTmux()
        writeProbe(project, applyPlugin = true, optIn = true, leak = "custom")
        val pidFile = project.resolve("server.pid")

        try {
            val failure = runAndFail(project)

            assertTrue("left a tmux server running: custom.sock" in failure, "the build did not name the socket")
            assertTrue(!serverAlive(pidFile), "the server was left running")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `a server on a socket in a subdirectory of the quarantine is found too`(@TempDir project: Path) {
        requireTmux()
        writeProbe(project, applyPlugin = true, optIn = true, leak = "nested")
        val pidFile = project.resolve("server.pid")

        try {
            val failure = runAndFail(project)

            assertTrue("left a tmux server running: sub/x.sock" in failure, "the build did not name the socket")
            assertTrue(!serverAlive(pidFile), "the server was left running")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `a server on a socket in the temporary directory the JVM was given is found too`(@TempDir project: Path) {
        requireTmux()
        writeProbe(project, applyPlugin = true, optIn = true, leak = "javatmp")
        val pidFile = project.resolve("server.pid")

        try {
            val failure = runAndFail(project)

            assertTrue("left a tmux server running: j/x.sock" in failure, "the build did not name the socket")
            assertTrue(!serverAlive(pidFile), "the server was left running")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `a socket root too long to leave room for a socket is refused before the task runs`(@TempDir project: Path) {
        writeProbe(project, applyPlugin = true, optIn = true)
        val tooLong = socketRoot.resolve("r".repeat(41))

        val failure = runAndFail(project, "-PlibtmuxSocketRoot=$tooLong")

        assertTrue("too long to leave room for a socket under it" in failure, "the build did not refuse the root")
        assertTrue(!Files.exists(tooLong), "a directory was made under a root that was refused")
    }

    @Test
    fun `a Test task is finalized by the check, with the tmux its libtmux tmux property names`(@TempDir project: Path) {
        requireTmux()
        val noop = noopKillServer(project)
        val pidFile = project.resolve("server.pid")
        writeTestTaskProbe(project, noop, pidFile)

        try {
            val failure =
                GradleRunner.create()
                    .withProjectDir(project.toFile())
                    .withPluginClasspath()
                    .withEnvironment(System.getenv() + inAPane)
                    .withArguments("test", "-PlibtmuxSocketRoot=$socketRoot", "--console=plain")
                    .buildAndFail()
                    .output

            assertTrue(
                "j/x.sock still answers after kill-server, so its quarantine was kept" in failure,
                "the finalizer did not run the tmux the Test task named",
            )
            assertTrue(serverAlive(pidFile), "the control never left a server running")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `temporary files a cancelled run left are cleared before the next run`(@TempDir project: Path) {
        writeProbe(project, applyPlugin = true, optIn = true)
        val directory = leftBehindByAnEarlierRun(project)
        Files.writeString(Files.createDirectories(directory.resolve("junit-1")).resolve("file"), "x")

        val third = run(project)

        assumeTrue("name=${directory.fileName}" in third.text, "the build ran in another daemon")
        assertEquals("0", third.report.getValue("TMUX_TMPDIR").getValue("entries"), "the task started on old debris")
        assertTrue(!Files.exists(directory), "the quarantine was not removed after the run")
    }

    @Test
    fun `a socket a cancelled run left is refused, not silently cleared`(@TempDir project: Path) {
        writeProbe(project, applyPlugin = true, optIn = true)
        val directory = leftBehindByAnEarlierRun(project)
        val left = directory.resolve("left.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(left)) }

        val output = runEither(project)

        val inThisDaemon = "contains a stale entry" in output || "name=${directory.fileName}" in output
        assumeTrue(inThisDaemon, "the build ran in another daemon")
        assertTrue("contains a stale entry: left.sock" in output, "the build did not name the socket it refused")
    }

    @Test
    fun `a socket file nothing answers on is not a leak`(@TempDir project: Path) {
        requireTmux()
        writeProbe(project, applyPlugin = true, optIn = true, leak = "stale")
        val pidFile = project.resolve("server.pid")

        try {
            val ran = run(project)

            assertEquals("true", ran.report.getValue("stale").getValue("socket-remains"), "no stale socket was left")
            assertTrue("left a tmux server running" !in ran.text, "a socket nothing answers on was reported")
            assertTrue(socketRoot.toFile().listFiles().orEmpty().isEmpty(), "the quarantine was not removed")
        } finally {
            stopServer(pidFile)
        }
    }

    @Test
    fun `a link to a socket outside the quarantine is neither followed nor stopped`(@TempDir project: Path) {
        requireTmux()
        val outside = Files.createDirectories(socketRoot).resolve("outside.sock")
        val tmux = realTmux()
        ProcessBuilder(tmux, "-S", outside.toString(), "-f", "/dev/null", "new-session", "-d", "-s", "outside", "cat")
            .redirectErrorStream(true)
            .start()
            .let { it.inputStream.readAllBytes(); it.waitFor() }
        try {
            writeProbe(project, applyPlugin = true, optIn = true, leak = "link", outside = outside)

            val ran = run(project)

            assertTrue("left a tmux server running" !in ran.text, "a link was followed to a server it does not own")
            assertEquals(0, askServer(tmux, outside), "the server outside the quarantine was stopped")
        } finally {
            ProcessBuilder(tmux, "-S", outside.toString(), "kill-server").redirectErrorStream(true).start().let {
                it.inputStream.readAllBytes()
                it.waitFor()
            }
        }
    }

    @Test
    fun `the quarantine is kept and the build fails when kill-server does not stop the server`(
        @TempDir project: Path,
    ) {
        requireTmux()
        val noop = noopKillServer(project)
        writeProbe(project, applyPlugin = true, optIn = true, leak = "custom")
        val pidFile = project.resolve("server.pid")

        try {
            val failure = runAndFail(project, "-PlibtmuxTmux=$noop")

            assertTrue(
                "custom.sock still answers after kill-server, so its quarantine was kept" in failure,
                "the build did not say the quarantine was kept",
            )
            assertTrue(serverAlive(pidFile), "the control never left a server running")
            assertTrue(socketRoot.toFile().listFiles().orEmpty().isNotEmpty(), "the quarantine was removed")
        } finally {
            stopServer(pidFile)
        }
    }

    // A quarantine an earlier run of the same task in this daemon left. Where it is depends on the daemon, so
    // a build that landed in another one has nothing to find and the case is skipped, not passed.
    private fun leftBehindByAnEarlierRun(project: Path): Path {
        val first = run(project).report.getValue("TMUX_TMPDIR").getValue("name")
        val directory = socketRoot.resolve(first)
        val again = run(project).report.getValue("TMUX_TMPDIR").getValue("name")
        assumeTrue(first == again, "the two builds ran in different daemons")
        Files.createDirectories(
            directory,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            ),
        )
        return directory
    }

    private fun compile(source: Path, classes: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
    }

    // A tmux that answers kill-server with success and does nothing, so a server it was told to stop stays up.
    private fun noopKillServer(project: Path): Path {
        val noop = Files.writeString(
            project.resolve("tmux-noop"),
            "#!/bin/sh\nfor a in \"$@\"; do [ \"\$a\" = kill-server ] && exit 0; done\nexec ${realTmux()} \"$@\"\n",
        )
        Files.setPosixFilePermissions(noop, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        return noop
    }

    /** A build whose Test task starts a server on a socket in the JVM's temporary directory and leaves it. */
    private fun writeTestTaskProbe(project: Path, tmux: Path, pidFile: Path) {
        val classes = Files.createDirectories(project.resolve("classes"))
        val source = Files.writeString(project.resolve("Dummy.java"), "public class Dummy {}\n")
        compile(source, classes)
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"probe\"\n")
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
            import java.nio.file.Files
            import java.nio.file.Path

            plugins { java; id("libtmux.tmux-quarantine") }
            tasks.test {
                testClassesDirs = files("classes")
                classpath = files("classes")
                systemProperty("libtmux.tmux", "$tmux")
                failOnNoDiscoveredTests = false
                // After the plugin's doFirst, which gives the task its environment.
                doLast {
                    val directory = Path.of(environment.getValue("TMUX_TMPDIR").toString())
                    val socket = Files.createDirectories(directory.resolve("j")).resolve("x.sock")
                    val start = listOf("tmux", "-S", socket.toString(), "-f", "/dev/null")
                    val create = start + listOf("new-session", "-d", "-s", "leak", "cat")
                    ProcessBuilder(create).inheritIO().start().waitFor()
                    val ask = ProcessBuilder("tmux", "-S", socket.toString(), "display-message", "-p", "#{pid}").start()
                    Files.writeString(Path.of("$pidFile"), String(ask.inputStream.readAllBytes()).trim())
                }
            }
            """.trimIndent(),
        )
    }

    private fun assertQuarantined(seen: Map<String, Map<String, String>>) {
        for (selector in listOf("TMUX", "TMUX_PANE", "LIBTMUX_SOCKET", "LIBTMUX_SOCKET_PATH")) {
            assertEquals("false", seen.getValue(selector).getValue("present"), "$selector reached the child")
        }
        val tmpdir = seen.getValue("TMUX_TMPDIR")
        assertEquals("true", tmpdir.getValue("present"), "the child has no TMUX_TMPDIR")
        assertEquals("false", tmpdir.getValue("unchanged"), "TMUX_TMPDIR was inherited")
        assertEquals("true", tmpdir.getValue("under-root"), "TMUX_TMPDIR is off the socket root")
        assertEquals("rwx------", tmpdir.getValue("mode"), "TMUX_TMPDIR is readable by others")
        assertEquals("true", tmpdir.getValue("is-java-tmpdir"), "the JVM's temporary directory is outside TMUX_TMPDIR")
        assertEquals("true", seen.getValue("LIBTMUX_TOOLSETS").getValue("unchanged"), "an unrelated variable changed")
    }

    private fun requireTmux() {
        val found = System.getenv("PATH").orEmpty().split(':').any { Files.isExecutable(Path.of(it, "tmux")) }
        assertTrue(found, "no tmux on PATH, so the finalizer cases cannot run")
    }

    private fun realTmux(): String =
        System.getenv("PATH").orEmpty().split(':').map { Path.of(it, "tmux") }.first(Files::isExecutable).toString()

    // The exit status of asking a server on this socket whether it is there.
    private fun askServer(tmux: String, socket: Path): Int =
        ProcessBuilder(tmux, "-S", socket.toString(), "display-message", "-p", "ok")
            .redirectErrorStream(true)
            .start()
            .let {
                it.inputStream.readAllBytes()
                it.waitFor()
            }

    private fun serverAlive(pidFile: Path): Boolean =
        Files.isRegularFile(pidFile) &&
            ProcessHandle.of(Files.readString(pidFile).trim().toLong()).map { it.isAlive }.orElse(false)

    // A server this test started, stopped by its pid so a failing case leaves nothing behind.
    private fun stopServer(pidFile: Path) {
        if (Files.isRegularFile(pidFile)) {
            ProcessHandle.of(Files.readString(pidFile).trim().toLong()).ifPresent { it.destroyForcibly() }
        }
    }

    private class Run(val text: String, val report: Map<String, Map<String, String>>)

    /** A build with one JavaExec task `probe` that runs a program reporting on its environment. */
    private fun writeProbe(
        project: Path,
        applyPlugin: Boolean,
        optIn: Boolean,
        leak: String = "",
        outside: Path? = null,
    ) {
        val classes = Files.createDirectories(project.resolve("classes"))
        val source = project.resolve("Probe.java")
        val given = inAPane.entries.joinToString(", ") { "{\"${it.key}\", \"${it.value}\"}" }
        Files.writeString(
            source,
            """
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.attribute.PosixFilePermissions;
            import java.util.ArrayList;
            import java.util.List;

            public class Probe {
                // Starts a server the way a task that leaves one does, and records the server's pid.
                static void leak(String[] arguments) throws Exception {
                    String mode = arguments[0];
                    Path tmpdir = Path.of(System.getenv("TMUX_TMPDIR"));
                    if (mode.equals("link")) {
                        Files.createSymbolicLink(tmpdir.resolve("link.sock"), Path.of(arguments[2]));
                        return;
                    }
                    Path socket = switch (mode) {
                        case "custom" -> tmpdir.resolve("custom.sock");
                        case "nested" -> Files.createDirectories(tmpdir.resolve("sub")).resolve("x.sock");
                        case "javatmp" ->
                            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")).resolve("j"))
                                .resolve("x.sock");
                        case "stale" -> tmpdir.resolve("stale.sock");
                        default -> null;
                    };
                    List<String> flags = socket == null ? List.of() : List.of("-S", socket.toString());
                    List<String> start = new ArrayList<>(List.of("tmux"));
                    start.addAll(flags);
                    start.addAll(List.of("-f", "/dev/null", "new-session", "-d", "-s", "leak", "cat"));
                    new ProcessBuilder(start).inheritIO().start().waitFor();
                    List<String> ask = new ArrayList<>(List.of("tmux"));
                    ask.addAll(flags);
                    ask.addAll(List.of("display-message", "-p", "#{pid}"));
                    String pid = new String(new ProcessBuilder(ask).start().getInputStream().readAllBytes()).trim();
                    Files.writeString(Path.of(arguments[1]), pid);
                    if (mode.equals("stale")) {
                        ProcessHandle server = ProcessHandle.of(Long.parseLong(pid)).orElseThrow();
                        server.destroyForcibly();
                        for (int attempt = 0; attempt < 100 && server.isAlive(); attempt++) Thread.sleep(50);
                        System.out.println("PROBE stale socket-remains=" + Files.exists(socket));
                    }
                    if (mode.equals("leak-and-fail")) System.exit(3);
                }

                public static void main(String[] arguments) throws Exception {
                    if (arguments.length > 1) {
                        leak(arguments);
                        return;
                    }
                    String[][] given = {$given};
                    for (String[] variable : given) {
                        String actual = System.getenv(variable[0]);
                        System.out.println("PROBE " + variable[0] + " present=" + (actual != null)
                            + " unchanged=" + variable[1].equals(actual));
                    }
                    String tmpdir = System.getenv("TMUX_TMPDIR");
                    if (tmpdir != null) {
                        String mode = "absent";
                        if (Files.isDirectory(Path.of(tmpdir))) {
                            mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(tmpdir)));
                        }
                        System.out.println("PROBE TMUX_TMPDIR under-root=" + tmpdir.startsWith("$socketRoot")
                            + " mode=" + mode
                            + " is-java-tmpdir=" + tmpdir.equals(System.getProperty("java.io.tmpdir"))
                            + " name=" + Path.of(tmpdir).getFileName()
                            + " entries=" + (Files.isDirectory(Path.of(tmpdir))
                                ? Files.list(Path.of(tmpdir)).count() : -1));
                    }
                }
            }
            """.trimIndent(),
        )
        compile(source, classes)
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"probe\"\n")
        val plugins = if (applyPlugin) "plugins { id(\"libtmux.tmux-quarantine\") }" else ""
        val optInFunction =
            if (applyPlugin) {
                "@Suppress(\"UNCHECKED_CAST\") " +
                    "val quarantineTmux = extra[\"quarantineTmux\"] as (TaskProvider<JavaExec>) -> Unit"
            } else {
                ""
            }
        val link = if (outside != null) ", \"$outside\"" else ""
        val arguments = if (leak.isNotEmpty()) "args(\"$leak\", \"${project.resolve("server.pid")}\"$link)" else ""
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
            $plugins
            $optInFunction
            val probe = tasks.register<JavaExec>("probe") {
                classpath = files("classes")
                mainClass = "Probe"
                $arguments
            }
            ${if (optIn) "quarantineTmux(probe)" else ""}
            """.trimIndent(),
        )
    }

    private fun runEither(project: Path): String =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withEnvironment(System.getenv() + inAPane)
            .withArguments("probe", "-PlibtmuxSocketRoot=$socketRoot", "--console=plain")
            .run()
            .output

    private fun runAndFail(project: Path, vararg arguments: String): String =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withEnvironment(System.getenv() + inAPane)
            .withArguments("probe", "-PlibtmuxSocketRoot=$socketRoot", "--console=plain", *arguments)
            .buildAndFail()
            .output

    private fun run(project: Path, vararg arguments: String): Run {
        val text =
            GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withEnvironment(System.getenv() + inAPane)
                .withArguments("probe", "-PlibtmuxSocketRoot=$socketRoot", "--console=plain", *arguments)
                .build()
                .output
        val report = mutableMapOf<String, MutableMap<String, String>>()
        text.lines().filter { it.startsWith("PROBE ") }.forEach { line ->
            val words = line.removePrefix("PROBE ").split(' ')
            val facts = report.getOrPut(words.first()) { mutableMapOf() }
            words.drop(1).forEach { facts[it.substringBefore('=')] = it.substringAfter('=') }
        }
        return Run(text, report)
    }
}
