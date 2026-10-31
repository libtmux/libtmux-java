package io.github.libtmux.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What a JavaExec task hands its child under libtmux.tmux-quarantine, with and without opting in.
 *
 * The child is a compiled program that reports on its own environment, and the build that runs it
 * starts from the environment of a pane, so the first case would fail on a plugin that did not scrub.
 * The child prints whether a variable is present and whether it still holds the value the build was
 * given, never a value: an environment holds secrets, and a failure is copied into logs.
 */
class TmuxQuarantineTest {

    private val socketRoot: Path =
        Path.of(System.getProperty("java.io.tmpdir")).resolve("tq" + ProcessHandle.current().pid())

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

    private fun assertQuarantined(seen: Map<String, Map<String, String>>) {
        for (selector in listOf("TMUX", "TMUX_PANE", "LIBTMUX_SOCKET", "LIBTMUX_SOCKET_PATH")) {
            assertEquals("false", seen.getValue(selector).getValue("present"), "$selector reached the child")
        }
        val tmpdir = seen.getValue("TMUX_TMPDIR")
        assertEquals("true", tmpdir.getValue("present"), "the child has no TMUX_TMPDIR")
        assertEquals("false", tmpdir.getValue("unchanged"), "TMUX_TMPDIR was inherited")
        assertEquals("true", tmpdir.getValue("under-root"), "TMUX_TMPDIR is off the socket root")
        assertEquals("rwx------", tmpdir.getValue("mode"), "TMUX_TMPDIR is readable by others")
        assertEquals("true", seen.getValue("LIBTMUX_TOOLSETS").getValue("unchanged"), "an unrelated variable changed")
    }

    private class Run(val text: String, val report: Map<String, Map<String, String>>)

    /** A build with one JavaExec task `probe` that runs a program reporting on its environment. */
    private fun writeProbe(project: Path, applyPlugin: Boolean, optIn: Boolean) {
        val classes = Files.createDirectories(project.resolve("classes"))
        val source = project.resolve("Probe.java")
        val given = inAPane.entries.joinToString(", ") { "{\"${it.key}\", \"${it.value}\"}" }
        Files.writeString(
            source,
            """
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.attribute.PosixFilePermissions;

            public class Probe {
                public static void main(String[] arguments) throws Exception {
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
                            + " mode=" + mode);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"probe\"\n")
        Files.writeString(
            project.resolve("build.gradle.kts"),
            """
            ${if (applyPlugin) "plugins { id(\"libtmux.tmux-quarantine\") }" else ""}
            ${if (applyPlugin) "@Suppress(\"UNCHECKED_CAST\") val quarantineTmux = extra[\"quarantineTmux\"] as (TaskProvider<JavaExec>) -> Unit" else ""}
            val probe = tasks.register<JavaExec>("probe") {
                classpath = files("classes")
                mainClass = "Probe"
            }
            ${if (optIn) "quarantineTmux(probe)" else ""}
            """.trimIndent(),
        )
    }

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
