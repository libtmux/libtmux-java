package io.github.libtmux.kotlin

import io.github.libtmux.Server
import io.github.libtmux.WakeReason
import io.github.libtmux.junit5.TmuxExtension
import io.github.libtmux.junit5.TmuxSocketPath
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(TmuxExtension::class)
class WaitsTest {

    @Test
    fun `a signal ends the wait`(server: Server) {
        val channel = server.channel("kotlin-signal")
        channel.drain()

        val outcome =
            runBlocking {
                val waiting = async { channel.await(5.seconds) }
                delay(200.milliseconds)
                assertFalse(waiting.isCompleted)
                channel.signal()
                waiting.await()
            }

        assertEquals(WakeReason.SIGNALLED, outcome)
    }

    @Test
    fun `cancelling a wait is not a timeout`(server: Server, socket: TmuxSocketPath) {
        val channel = server.channel("kotlin-cancel")
        channel.drain()
        val needle = socket.path().toString()

        runBlocking {
            val waiting = async { channel.await(5.seconds) }
            val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
            while (!waiterPresent(needle) && System.nanoTime() < deadline) {
                delay(20.milliseconds)
            }
            assertTrue(waiterPresent(needle), "the wait never reached tmux")
            waiting.cancel()
            val failure = runCatching { withTimeout(1.seconds) { waiting.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException && failure !is TimeoutCancellationException)
        }
    }

    @Test
    fun `a wait runs on the dispatcher it is given`(server: Server) {
        val channel = server.channel("kotlin-dispatcher")
        channel.drain()
        val recording = RecordingDispatcher()

        val outcome =
            runBlocking {
                val waiting = async { channel.await(5.seconds, recording) }
                delay(200.milliseconds)
                channel.signal()
                waiting.await()
            }

        assertEquals(WakeReason.SIGNALLED, outcome)
        assertTrue(recording.dispatched.get() > 0, "the wait never ran on the given dispatcher")
    }

    @Test
    fun `a negative channel wait is rejected`(server: Server) {
        val channel = server.channel("kotlin-negative")

        assertFailsWith<IllegalArgumentException> {
            runBlocking { channel.await((-1).milliseconds) }
        }
    }

    @Test
    fun `run returns the command's status against a real pane`(server: Server) {
        val pane = server.sessions()[0].windows()[0].panes()[0]

        val result = runBlocking { pane.run("true", 10.seconds) }

        assertTrue(result.succeeded())
    }

    @Test
    fun `cancelling run ends the wait promptly`(server: Server, socket: TmuxSocketPath) {
        val pane = server.sessions()[0].windows()[0].panes()[0]
        val needle = socket.path().toString()

        runBlocking {
            val running = async { pane.run("sleep 30", 30.seconds) }
            val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
            while (!waiterPresent(needle) && System.nanoTime() < deadline) {
                delay(20.milliseconds)
            }
            assertTrue(waiterPresent(needle), "run's wait never reached tmux")
            running.cancel()
            val failure = runCatching { withTimeout(1.seconds) { running.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException && failure !is TimeoutCancellationException)
        }
    }
}

// ProcessHandle rather than /proc, which macOS does not have.
private fun waiterPresent(socket: String): Boolean =
    ProcessHandle.allProcesses().anyMatch { process ->
        val argv = process.info().arguments().orElse(emptyArray())
        "wait-for" in argv && socket in argv
    }

/** Dispatches to [Dispatchers.IO], counting what it was given. */
internal class RecordingDispatcher : CoroutineDispatcher() {
    val dispatched = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched.incrementAndGet()
        Dispatchers.IO.dispatch(context, block)
    }
}
