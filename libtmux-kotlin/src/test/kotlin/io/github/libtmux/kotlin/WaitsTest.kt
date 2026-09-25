package io.github.libtmux.kotlin

import io.github.libtmux.Server
import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.Session
import io.github.libtmux.WakeReason
import io.github.libtmux.junit5.TmuxExtension
import io.github.libtmux.junit5.TmuxSocketPath
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
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

    @Test
    fun `control attaches a working client against real tmux`(server: Server) {
        val session = server.sessions()[0]

        val client = runBlocking { server.control(session, 5.seconds) }

        try {
            assertTrue(client.isAlive())
            assertEquals(
                session.id().value(),
                client.send("display-message", "-p", "#{session_id}").lines()[0],
            )
        } finally {
            client.close()
        }
    }

    /**
     * tmux answers control-mode attach as fast as it can reply at all, so racing a real one to
     * freeze it never wins: the reply is written before a poll of this JVM's own children can see
     * the process exists. Instead, the fixture's binary is a wrapper that blocks the `-C` attach on
     * a FIFO nothing ever writes to, an ordinary session command passing through unchanged, so the
     * wait is stalled by construction rather than by timing.
     */
    @Test
    fun `cancelling control ends promptly and kills the stalled process`() {
        val stall = StalledControlFixture.start()
        try {
            runBlocking {
                val attaching = async(Dispatchers.IO) { stall.server.control(stall.session, 30.seconds) }

                val pid = awaitControlClientPid(stall.socket, 5.seconds)
                assertNotNull(pid, "the wrapped control client never started")
                assertFalse(attaching.isCompleted, "attach finished before it could be caught stalled")

                attaching.cancel()
                val failure = runCatching { withTimeout(2.seconds) { attaching.await() } }.exceptionOrNull()
                assertTrue(
                    failure is CancellationException && failure !is TimeoutCancellationException,
                    "expected a plain CancellationException, got $failure",
                )

                assertTrue(
                    processGone(pid, 5.seconds),
                    "the stalled control client process outlived cancellation",
                )
            }
        } finally {
            stall.close()
        }
    }
}

/**
 * A server whose binary blocks a `-C` attach on a FIFO nothing writes to, so the reply
 * [Server.control] waits for never arrives. Every other invocation — session creation, the
 * identity check `control` runs before attaching — has no `-C` and passes straight through to the
 * real tmux.
 */
private class StalledControlFixture private constructor(
    private val root: Path,
    val server: Server,
    val session: Session,
) {
    val socket: String = root.resolve("s").toString()

    fun close() {
        try {
            server.killServer()
        } finally {
            server.close()
            root.toFile().deleteRecursively()
        }
    }

    companion object {
        fun start(): StalledControlFixture {
            val root = Files.createTempDirectory(Path.of("/tmp/libtmux-java-test"), "kt-control-stall-")
            val gate = root.resolve("gate")
            val wrapper = root.resolve("wrap.sh")
            val tmuxBinary = System.getProperty("libtmux.tmux", "tmux")
            check(ProcessBuilder("mkfifo", gate.toString()).start().waitFor() == 0) { "mkfifo failed" }
            Files.writeString(
                wrapper,
                """
                #!/bin/sh
                for arg; do
                    if [ "${'$'}arg" = "-C" ]; then
                        read line < "$gate"
                        break
                    fi
                done
                exec "$tmuxBinary" "${'$'}@"
                """.trimIndent(),
            )
            wrapper.toFile().setExecutable(true)
            val conf = root.resolve("tmux.conf")
            Files.writeString(conf, "")
            val socket = root.resolve("s")

            val server =
                Server.open(
                    ServerConfig.builder()
                        .binary(wrapper.toString())
                        .endpoint(ServerEndpoint.socketPath(socket))
                        .configFile(conf)
                        .build(),
                )
            val created = server.cmd("new-session", "-d", "-s", "stall")
            check(created.succeeded()) { "could not start the stalled fixture: ${created.stderr()}" }
            return StalledControlFixture(root, server, server.sessions()[0])
        }
    }
}

// ProcessHandle rather than /proc, which macOS does not have.
private fun waiterPresent(socket: String): Boolean =
    ProcessHandle.allProcesses().anyMatch { process ->
        val argv = process.info().arguments().orElse(emptyArray())
        "wait-for" in argv && socket in argv
    }

// A direct child of this JVM, not a system-wide scan of every process on the machine.
private fun controlClientPid(socket: String): Long? =
    ProcessHandle.current()
        .children()
        .filter { process ->
            val argv = process.info().arguments().orElse(emptyArray())
            "-C" in argv && socket in argv
        }
        .findFirst()
        .map(ProcessHandle::pid)
        .orElse(null)

private suspend fun awaitControlClientPid(socket: String, timeout: Duration): Long? {
    val end = System.nanoTime() + timeout.inWholeNanoseconds
    while (System.nanoTime() < end) {
        val pid = controlClientPid(socket)
        if (pid != null) {
            return pid
        }
        delay(20.milliseconds)
    }
    return null
}

private suspend fun processGone(pid: Long, timeout: Duration): Boolean {
    val end = System.nanoTime() + timeout.inWholeNanoseconds
    while (System.nanoTime() < end) {
        if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            return true
        }
        delay(20.milliseconds)
    }
    return false
}

/** Dispatches to [Dispatchers.IO], counting what it was given. */
internal class RecordingDispatcher : CoroutineDispatcher() {
    val dispatched = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched.incrementAndGet()
        Dispatchers.IO.dispatch(context, block)
    }
}
