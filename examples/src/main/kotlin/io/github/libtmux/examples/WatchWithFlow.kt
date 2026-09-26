package io.github.libtmux.examples

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.control.Delivery
import io.github.libtmux.kotlin.Server
import io.github.libtmux.kotlin.await
import io.github.libtmux.kotlin.send
import io.github.libtmux.kotlin.sessions
import io.github.libtmux.kotlin.withControl
import io.github.libtmux.kotlin.withServer
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a pane's output as a `Flow`, then gives up on a wait by cancelling it.
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
suspend fun watchWithFlow(socket: Path, deadline: Duration): String {
    val config = ServerConfig.builder().endpoint(ServerEndpoint.socketPath(socket)).build()
    return withServer(config) { server: Server ->
        val session = server.sessions().first()
        withControl(server, session) { control ->
            // control.output(...) is a cold Flow: the subscription it opens on first collection
            // closes when collection ends, is cancelled, or throws — nothing to close by hand. The
            // command goes in onSubscribed: sent before collecting, its output could arrive before
            // there was a subscription to hold it.
            val seen = StringBuilder()
            val echoed =
                withTimeoutOrNull(deadline) {
                    control.output(capacity = 32) {
                        control.send("send-keys", "-t", session.name, "echo flowed", "Enter")
                    }.map { Delivery.kept(it).data }.first { chunk ->
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
