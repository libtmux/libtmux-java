package io.github.libtmux.kotlin

import io.github.libtmux.Server
import io.github.libtmux.WakeReason
import io.github.libtmux.junit5.TmuxExtension
import io.github.libtmux.junit5.TmuxSocketPath
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
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
    fun `a negative channel wait is rejected`(server: Server) {
        val channel = server.channel("kotlin-negative")

        assertFailsWith<IllegalArgumentException> {
            runBlocking { channel.await((-1).milliseconds) }
        }
    }
}

// ProcessHandle rather than /proc, which macOS does not have.
private fun waiterPresent(socket: String): Boolean =
    ProcessHandle.allProcesses().anyMatch { process ->
        val argv = process.info().arguments().orElse(emptyArray())
        "wait-for" in argv && socket in argv
    }
