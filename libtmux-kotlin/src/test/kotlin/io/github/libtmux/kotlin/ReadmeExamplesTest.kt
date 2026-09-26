package io.github.libtmux.kotlin

import io.github.libtmux.exception.CardinalityException
import io.github.libtmux.exception.CommandRejectedException
import io.github.libtmux.exception.ControlEndedException
import io.github.libtmux.exception.DispatchException
import io.github.libtmux.exception.LibTmuxException
import io.github.libtmux.exception.MalformedResponseException
import io.github.libtmux.exception.ServerUnavailableException
import io.github.libtmux.exception.TargetGoneException
import io.github.libtmux.exception.UnencodableTextException
import io.github.libtmux.exception.UnsupportedFeatureException
import io.github.libtmux.junit5.TmuxExtension
import io.github.libtmux.kotlin.query.active
import io.github.libtmux.kotlin.query.command
import io.github.libtmux.kotlin.query.name
import io.github.libtmux.transport.DispatchOutcome
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.github.libtmux.Server as JavaServer

/**
 * Every Kotlin example in this module's README, run against a real tmux server.
 *
 * The documentation module compiles and runs the Java fences in the documentation, and cannot read Kotlin ones —
 * it would need the Kotlin compiler in-process to do it. So the Kotlin examples are executed here
 * instead, one test per README section, and the README says which test covers it.
 */
@ExtendWith(TmuxExtension::class)
class ReadmeExamplesTest {

    @Test
    fun `wrapper classes, not the Java types directly`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val session = server.newSession("build")

            assertEquals("build", session.name)
            assertTrue(server.admissionBound > 0)
        }
    }

    @Test
    fun `declare a session, its windows, and their splits with the DSL`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val session = server.newSession {
                name = "editors"
                window { name = "left" }
                window {
                    name = "right"
                    split { toRight(); percent(30) }
                }
            }

            assertEquals(2, session.windows.size)
            assertEquals("left", session.windows[0].name)
            assertEquals("right", session.windows[1].name)
        }
    }

    @Test
    fun `send keys, capture output, run a command`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val session = server.newSession("keys-demo")
            val pane = session.activeWindow?.activePane ?: error("no active pane")

            pane.sendLine("echo ready")
            pane.awaitText("ready", timeout = 5.seconds)
            val lines = pane.capture()

            val run = pane.run("echo hi && exit 3", timeout = 5.seconds)
            assertEquals(3, run.exitStatus.orNull())
            assertTrue(lines.isNotEmpty())
        }
    }

    @Test
    fun `query with typed fields on the companion`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            server.newSession("query-demo")
            val editors = server.panes(Pane.command startsWith "nvim")
            val activePanes = server.panes(Pane.active.isTrue())

            assertTrue(editors.size >= 0)
            assertTrue(activePanes.isNotEmpty())
        }
    }

    @Test
    fun `a lookup miss throws, a lookup surplus throws too`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val session = server.newSession("cardinality-demo")

            assertEquals(session.id, server.session(Session.name eq "cardinality-demo").id)
            assertEquals(null, server.sessionOrNull(Session.name eq "no-such-session-at-all"))
            try {
                server.session(Session.name eq "no-such-session-at-all")
                throw AssertionError("expected CardinalityException.NoMatch")
            } catch (expected: CardinalityException.NoMatch) {
                // expected
            }
        }
    }

    @Test
    fun `exhaustive when over the sealed failure tree`() {
        fun nextStep(failure: LibTmuxException): String =
            when (failure) {
                is TargetGoneException -> "look it up again"
                is ServerUnavailableException -> "start a server"
                is CommandRejectedException -> "change the request"
                is DispatchException -> if (failure.safeToRetry()) "send it again" else "read state first"
                is ControlEndedException -> "attach again"
                is UnsupportedFeatureException -> "do without"
                is UnencodableTextException -> "use a UTF-8 locale"
                is MalformedResponseException -> "report it"
                is CardinalityException.NoMatch -> "nothing matched"
                is CardinalityException.MultipleMatches -> "ambiguous"
            }

        assertEquals("ambiguous", nextStep(CardinalityException.MultipleMatches("many", 3)))
        assertEquals(
            "send it again",
            nextStep(DispatchException.TimedOut("late", DispatchOutcome.NOT_DISPATCHED, null)),
        )
    }

    @Test
    fun `a subscription as a cold Flow`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val session = server.newSession("flow-demo")
            withControl(server, session) { control ->
                val step = control.output(capacity = 64) {
                    control.send("send-keys", "-t", session.name, "echo flowed", "Enter")
                }.first()
                val outcome = when (step) {
                    is io.github.libtmux.control.Delivery.Event -> "kept"
                    is io.github.libtmux.control.Delivery.Gap -> "lost ${step.missed}"
                }
                assertEquals("kept", outcome)
            }
        }
    }

    @Test
    fun `the live server as a StateFlow`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val session = server.newSession("live-demo")
            server.withLiveState(session) { live ->
                val view = live.first()

                assertTrue(view.epoch >= 0L)
            }
        }
    }

    @Test
    fun `retry using the safe-to-retry predicate`(javaServer: JavaServer) = runBlocking {
        withServer(javaServer.config()) { server ->
            val version = retryIfSafe(times = 3) { server.version() }
            assertTrue(version.major >= 3)
        }
    }
}
