package io.github.libtmux.kotlin

import io.github.libtmux.Pane_
import io.github.libtmux.Server
import io.github.libtmux.junit5.TmuxExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Every Kotlin example in this module's README, run against a real tmux server.
 *
 * `docs-tests` compiles and runs the Java fences in the documentation, and cannot read Kotlin ones —
 * it would need the Kotlin compiler in-process to do it. So the Kotlin examples are executed here
 * instead, one test per README section, and the README says which test covers it.
 */
@ExtendWith(TmuxExtension::class)
class ReadmeExamplesTest {

    @Test
    fun `what already works with no module at all`(server: Server) {
        // use {} works because Server is AutoCloseable; the trailing lambda is a SAM conversion.
        val session = server.newSession { it.named("build") }

        assertEquals("build", session.name())
        assertEquals(2, server.sessions().size)
    }

    @Test
    fun `absence as null`(server: Server) {
        val session = server.sessions()[0]
        val pane = session.windows()[0].panes()[0]

        assertNotNull(session.activeWindowOrNull())
        assertNotNull(session.activePaneOrNull())

        // Null before tmux 3.7, which cannot report it, and a Boolean after.
        val floats = pane.floatingOrNull()
        assertTrue(floats == null || floats == false)

        assertEquals(null, server.options().getOrNull("no-such-option-at-all"))

        session.options().set("status-left", "[libtmux]")
        assertEquals("[libtmux]", session.options().getOrNull("status-left"))
    }

    @Test
    fun `negation as an operator`(server: Server) {
        val active = Pane_.active().isTrue()

        assertEquals(active.negate().describe(), (!active).describe())
        // Kotlin's own filter takes a function; this overload takes the expression.
        assertEquals(1, server.panes().filter(active).size)
        assertEquals(0, server.panes().filter(!active).size)
        assertEquals(1, server.panes().filter(Pane_.command().startsWith("z")).size)
    }
}
