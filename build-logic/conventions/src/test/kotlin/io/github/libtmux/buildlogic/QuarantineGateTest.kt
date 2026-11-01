package io.github.libtmux.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The gate that fails a build when a module with tests runs them outside the quarantine. */
class QuarantineGateTest {

    @Test
    fun `modules that apply the quarantine and the refusal pass`(@TempDir project: Path) {
        writeBuild(project, "covered" to Module(plugin = true, refusal = true))

        val output = run(project).build().output

        assertTrue("every module with tests runs them under the tmux quarantine" in output, "the gate did not pass")
    }

    @Test
    fun `a module with tests and no quarantine plugin fails the build and is named`(@TempDir project: Path) {
        writeBuild(
            project,
            "covered" to Module(plugin = true, refusal = true),
            "bare" to Module(plugin = false, refusal = true),
        )

        val output = run(project).buildAndFail().output

        assertTrue("outside the tmux quarantine: :bare" in output, "the gate did not name the module")
    }

    @Test
    fun `a module with tests and no refusal fails the build and is named`(@TempDir project: Path) {
        writeBuild(
            project,
            "covered" to Module(plugin = true, refusal = true),
            "blind" to Module(plugin = true, refusal = false),
        )

        val output = run(project).buildAndFail().output

        assertTrue("outside the tmux quarantine: :blind" in output, "the gate did not name the module")
    }

    private class Module(val plugin: Boolean, val refusal: Boolean)

    // One project per module, each with the java plugin so it has a Test task. The refusal dependency is
    // declared and never resolved: the gate reads declarations only.
    private fun writeBuild(project: Path, vararg modules: Pair<String, Module>) {
        Files.writeString(
            project.resolve("settings.gradle.kts"),
            "rootProject.name = \"gate\"\ninclude(${modules.joinToString { "\"${it.first}\"" }})\n",
        )
        val gate = Path.of(System.getProperty("user.dir"), "src/main/kotlin/libtmux.tmux-quarantine-gate.gradle.kts")
        Files.writeString(project.resolve("build.gradle.kts"), "apply(from = \"${gate}\")\n")
        for ((name, module) in modules) {
            val directory = Files.createDirectories(project.resolve(name))
            val plugins = if (module.plugin) "plugins { java; id(\"libtmux.tmux-quarantine\") }" else "plugins { java }"
            val dependency =
                if (module.refusal) "dependencies { testRuntimeOnly(\"io.github.libtmux.build:test-sandbox\") }" else ""
            Files.writeString(directory.resolve("build.gradle.kts"), "$plugins\n$dependency\n")
        }
    }

    private fun run(project: Path): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments("everyTestModuleIsQuarantined", "--console=plain")
}
