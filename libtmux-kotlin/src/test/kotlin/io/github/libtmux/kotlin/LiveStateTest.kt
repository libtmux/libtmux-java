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

                // A burst: every window in one tmux invocation, so all the announcements land within
                // milliseconds and conflation, not the machine's speed, decides how many rebuilds
                // follow. Created one call at a time, a slow runner rebuilt after each and published
                // one view per window ("while a snapshot is in flight, further notifications
                // collapse into one rebuild" needs them to arrive while one is in flight).
                val burst = javaServer.batch()
                repeat(windowCount) { index ->
                    burst.add("new-window", "-d", "-t", "${sessionId.value()}:", "-n", "stress-$index")
                }
                check(burst.run().succeeded()) { "the burst was refused" }

                val finalView = withTimeout(30.seconds) {
                    live.first { view -> windowCountOf(view, sessionId) >= expectedTotal }
                }

                assertEquals(expectedTotal, windowCountOf(finalView, sessionId))
                assertTrue(finalView.epoch() > startEpoch, "epoch must have advanced")
                assertTrue(
                    finalView.epoch() - startEpoch < windowCount,
                    "conflation should have folded the $windowCount changes into fewer views than one " +
                        "per change (epoch went from $startEpoch to ${finalView.epoch()})",
                )
            }
        }

    private fun windowCountOf(view: ServerMirror.View, session: SessionId): Int =
        view.snapshot().windowsOf(session).size
}
