package io.github.libtmux.examples

import io.github.libtmux.Server
import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.kotlin.await
import io.github.libtmux.kotlin.deliveries
import io.github.libtmux.kotlin.kept
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a pane's output as a Flow, then gives up on a wait by cancelling it.
 *
 * ```
 * java -cp ... io.github.libtmux.examples.WatchWithFlowKt /tmp/libtmux-java-dev/demo/s
 * ```
 */
fun main(args: Array<String>) {
    val socket = Path.of(args.getOrElse(0) { "/tmp/libtmux-java-dev/demo/s" })
    runBlocking { println(watchWithFlow(socket, 10.seconds)) }
}

/**
 * Collects output until the line `echo` printed arrives, then starts a channel wait that nothing
 * will signal and cancels it after a moment. Cancelling ends the coroutine at once and is not a
 * timeout; the tmux client behind the wait is stopped with it.
 *
 * @return one line for each: whether the echo arrived, and whether the wait was cancelled
 */
suspend fun watchWithFlow(socket: Path, deadline: Duration): String =
    // Every libtmux call blocks its thread, so they run on Dispatchers.IO rather than on whatever
    // thread the caller's coroutine is using. The waits below suspend instead of blocking.
    withContext(Dispatchers.IO) {
        val config = ServerConfig.builder().endpoint(ServerEndpoint.socketPath(socket)).build()
        Server.open(config).use { server ->
            val session = server.sessions()[0]
            server.control(session).use { client ->
                // deliveries() closes the subscription when the flow ends, including by cancellation.
                val output = client.subscribeOutput(32)
                client.send("send-keys", "-t", session.name(), "echo flowed", "Enter")

                val seen = StringBuilder()
                val echoed =
                    withTimeoutOrNull(deadline) {
                        output.deliveries().map { it.kept().data() }.first { chunk ->
                            seen.append(chunk)
                            WatchPaneOutput.printedLine(seen.toString(), "flowed")
                        }
                    } != null

                val cancelled =
                    withTimeoutOrNull(200.milliseconds) { server.channel("never-signalled").await(30.seconds) } == null

                "echoed=$echoed\ncancelled=$cancelled"
            }
        }
    }
