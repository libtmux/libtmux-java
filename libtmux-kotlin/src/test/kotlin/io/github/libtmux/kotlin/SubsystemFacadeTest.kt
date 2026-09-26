package io.github.libtmux.kotlin

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every subsystem accessor (`Pane.options()`, `Server.hooks()`, ...) answers a wrapper class whose own
 * operations are `suspend`, the same contract [Server], [Session], [Window], [Pane] and [Client]
 * already carry. Before that wrapping, these accessors answered the raw Java handle, whose `get`/`set`
 * were plain blocking calls a non-suspend function could call directly; these fixtures fail to compile
 * only once the wrapping is in place.
 */
class SubsystemFacadeTest {

    @Test
    fun `Pane-options() is suspend, not callable from a non-suspend function`() {
        val fixture = """
            import io.github.libtmux.kotlin.Pane

            fun use(pane: Pane) {
                pane.options().get("status")
            }
        """.trimIndent()

        val result = KotlincHarness.compile(fixture)

        assertTrue(!result.succeeded, "expected a non-suspend caller of Options.get to fail:\n${result.diagnostics}")
        assertTrue(
            result.diagnostics.contains("suspend function", ignoreCase = true),
            "expected a suspend-context diagnostic, got:\n${result.diagnostics}",
        )
    }

    @Test
    fun `Server-hooks() is suspend, not callable from a non-suspend function`() {
        val fixture = """
            import io.github.libtmux.kotlin.Server
            import io.github.libtmux.kotlin.set

            fun use(server: Server) {
                server.hooks().set("after-new-window", "display-message hello")
            }
        """.trimIndent()

        val result = KotlincHarness.compile(fixture)

        assertTrue(!result.succeeded, "expected a non-suspend caller of Hooks.set to fail:\n${result.diagnostics}")
        assertTrue(
            result.diagnostics.contains("suspend function", ignoreCase = true),
            "expected a suspend-context diagnostic, got:\n${result.diagnostics}",
        )
    }

    @Test
    fun `Pane-options() compiles from a suspend function`() {
        val fixture = """
            import io.github.libtmux.kotlin.Pane

            suspend fun use(pane: Pane): String? = pane.options().get("status")
        """.trimIndent()

        val result = KotlincHarness.compile(fixture)

        assertTrue(result.succeeded, "expected a suspend caller to compile:\n${result.diagnostics}")
    }

    /** Collecting commands reaches no tmux, so a plain function builds a batch or a chain; only `run` suspends. */
    @Test
    fun `batch and chain steps are collected without suspending`() {
        val fixture = """
            import io.github.libtmux.kotlin.Batch
            import io.github.libtmux.kotlin.CommandChain

            fun collect(batch: Batch, chain: CommandChain) {
                batch.add("display-message", "-p", "one").add(listOf("display-message", "-p", "two"))
                chain.newWindow("w").splitLeftRight().splitTopBottom().sendLine("true").then("select-pane", "-L")
            }
        """.trimIndent()

        val result = KotlincHarness.compile(fixture)

        assertTrue(result.succeeded, "expected a non-suspend caller to collect steps:\n${result.diagnostics}")
    }
}
