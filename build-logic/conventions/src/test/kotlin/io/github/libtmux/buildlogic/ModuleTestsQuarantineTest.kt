package io.github.libtmux.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each module-tests build keeps its run away from the developer's tmux.
 *
 * These builds resolve nothing from this one, so the root gate cannot see them, and running them needs a staged
 * repository. This reads the lines that wire them, which a comment could satisfy.
 */
class ModuleTestsQuarantineTest {

    private val moduleTests: Path = Path.of(System.getProperty("user.dir"), "../../module-tests").normalize()

    private fun script(module: String): String =
        Files.readString(moduleTests.resolve(module).resolve("build.gradle.kts"))

    @Test
    fun `the java, kotlin and scala builds apply the quarantine and opt their run in`() {
        for (module in listOf("java", "kotlin", "scala")) {
            val text = script(module)

            assertTrue("libtmux.tmux-quarantine.gradle.kts" in text, "module-tests/$module applies no quarantine")
            assertTrue(
                "quarantineTmux(tasks.named<JavaExec>(\"run\")" in text,
                "module-tests/$module does not quarantine its run",
            )
        }
    }

    @Test
    fun `the cli build scrubs its children, gives them a TMUX_TMPDIR and stops what they leave`() {
        val text = script("cli")

        assertTrue("libtmux.tmux-quarantine.gradle.kts" in text, "module-tests/cli does not apply the quarantine")
        assertTrue("withoutServerSelectors(this)" in text, "module-tests/cli does not scrub its children")
        assertTrue("environment(\"TMUX_TMPDIR\"" in text, "module-tests/cli gives its children no TMUX_TMPDIR")
        assertTrue("stopServer(" in text, "module-tests/cli does not await the server it started")
        assertTrue("stopLeakedServers(" in text, "module-tests/cli does not stop the servers its children leave")
    }
}
