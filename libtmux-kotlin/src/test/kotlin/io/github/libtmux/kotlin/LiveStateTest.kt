package io.github.libtmux.kotlin

import io.github.libtmux.SessionId
import io.github.libtmux.junit5.TmuxExtension
import io.github.libtmux.snapshot.ServerMirror
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.github.libtmux.Server as JavaServer

/**
 * `withLiveState` is used throughout, not the bare `liveState`+`coroutineScope` it wraps:
 * `liveState`'s background pump runs until its scope ends by design, so a plain
 * `coroutineScope { liveState(...) }` never returns — structured concurrency waits for every child,
 * the pump included, and nothing inside it ever asks the pump to stop.
 */
@ExtendWith(TmuxExtension::class)
class LiveStateTest {

    @Test
    fun `the initial view is already populated, no empty window`(javaServer: JavaServer) = runBlocking {
        val server = Server.open(javaServer.config())
        val session = server.newSession("live-initial")

        server.withLiveState(session) { live ->
            val view = live.value

            assertEquals(0L, view.epoch())
            assertTrue(view.snapshot().sessions().any { it.id() == session.java.id() })
        }
    }

    @Test
    fun `many rapid changes eventually all land, with a strictly increasing epoch`(javaServer: JavaServer) =
        runBlocking {
            val server = Server.open(javaServer.config())
            val session = server.newSession("live-stress")
            val sessionId = session.java.id()
            val windowCount = 25
            val expectedTotal = windowCount + 1 // plus tmux's own first window

            server.withLiveState(session) { live ->
                val startEpoch = live.value.epoch()

                // A bursty producer: windows created back to back, far faster than ServerMirror's own
                // rebuild can keep up with one-for-one — exactly what its own conflation exists for
                // ("while a snapshot is in flight, further notifications collapse into one rebuild").
                repeat(windowCount) { index -> session.newWindow("stress-$index") }

                val finalView = withTimeout(30.seconds) {
                    live.first { view -> windowCountOf(view, sessionId) >= expectedTotal }
                }

                assertEquals(expectedTotal, windowCountOf(finalView, sessionId))
                assertTrue(finalView.epoch() > startEpoch, "epoch must have advanced")
                assertTrue(
                    finalView.epoch() < expectedTotal,
                    "conflation should have folded some of the $windowCount rapid changes into fewer " +
                        "rebuilds than one per change (epoch went from $startEpoch to ${finalView.epoch()})",
                )
            }
        }

    private fun windowCountOf(view: ServerMirror.View, session: SessionId): Int =
        view.snapshot().windowsOf(session).size
}
