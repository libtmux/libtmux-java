package io.github.libtmux.examples

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.control.Delivery
import io.github.libtmux.examples.WatchPaneOutput.printedLine
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
 * java -cp ... io.github.libtmux.examples.WatchWithFlowKt \
 *     /tmp/libtmux-java-dev/demo/s
 * ```
 */
fun main(args: Array<String>) {
    val socket = Path.of(args.getOrElse(0) { DEMO })
    runBlocking { println(watchWithFlow(socket, 10.seconds)) }
}

private const val DEMO = "/tmp/libtmux-java-dev/demo/s"

/**
 * Collects output until the line `echo` printed arrives, then starts a channel
 * wait that nothing will signal and cancels it after a moment. Cancelling ends
 * the coroutine at once and is not a timeout; the tmux client behind the wait
 * is stopped with it.
 *
 * @return one line for each: whether the echo arrived, and whether the wait was
 *   cancelled
 */
suspend fun watchWithFlow(socket: Path, deadline: Duration): String {
    val endpoint = ServerEndpoint.socketPath(socket)
    val config = ServerConfig.builder().endpoint(endpoint).build()
    return withServer(config) { server: Server ->
        val session = server.sessions().first()
        withControl(server, session) { control ->
            // control.output(...) is a cold Flow: the subscription it opens on
            // first collection closes when collection ends, is cancelled, or
            // throws, so there is nothing to close by hand. The command goes
            // in onSubscribed: sent before collecting, its output could arrive
            // before there was a subscription to hold it.
            val seen = StringBuilder()
            val target = session.name
            val sendEcho: suspend () -> Unit = {
                control.send("send-keys", "-t", target, "echo flowed", "Enter")
            }
            val echoed =
                withTimeoutOrNull(deadline) {
                    control.output(capacity = 32, sendEcho)
                        .map { Delivery.kept(it).data }
                        .first { chunk ->
                            seen.append(chunk)
                            printedLine(seen.toString(), "flowed")
                        }
                } != null

            val never = server.channel("never-signalled")
            val grace = 200.milliseconds
            val waited = withTimeoutOrNull(grace) { never.await(30.seconds) }
            val cancelled = waited == null

            "echoed=$echoed\ncancelled=$cancelled"
        }
    }
}
