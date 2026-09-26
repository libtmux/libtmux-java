// Compiled by the oldest Kotlin this module claims, against the jar it publishes, by the
// compileOldestConsumer task. Every facade class is reached, so a module a Kotlin 2.1 compiler
// cannot read fails to compile here.
package consumer

import io.github.libtmux.ServerConfig
import io.github.libtmux.kotlin.ExecutionPolicy
import io.github.libtmux.kotlin.Pane
import io.github.libtmux.kotlin.Server
import io.github.libtmux.kotlin.Session
import io.github.libtmux.kotlin.liveState
import io.github.libtmux.kotlin.newSession
import io.github.libtmux.kotlin.orNull
import io.github.libtmux.kotlin.panes
import io.github.libtmux.kotlin.query.active
import io.github.libtmux.kotlin.query.command
import io.github.libtmux.kotlin.query.name
import io.github.libtmux.kotlin.retryIfSafe
import io.github.libtmux.kotlin.send
import io.github.libtmux.kotlin.sendLine
import io.github.libtmux.kotlin.sessions
import io.github.libtmux.kotlin.version
import io.github.libtmux.kotlin.withControl
import io.github.libtmux.kotlin.withLiveState
import io.github.libtmux.kotlin.withServer
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

suspend fun reachEverything(config: ServerConfig, scope: CoroutineScope) {
    val policy = ExecutionPolicy.default(config)
    withServer(config, policy) { server: Server ->
        val session: Session =
            server.newSession {
                name = "consumer"
                window {
                    name = "editor"
                    split { toRight(); percent(30) }
                }
            }

        server.session(Session.name eq "consumer")
        server.sessionOrNull(Session.name eq "no-such-session")
        server.sessions()
        server.panes(Pane.command startsWith "nvim")

        val pane: Pane = session.activeWindow!!.activePane!!
        pane.sendLine("echo ready")
        pane.awaitText("ready", timeout = 1.seconds)
        pane.run("true", timeout = 1.seconds).exitStatus.orNull()

        retryIfSafe(times = 1) { server.version() }

        withControl(server, session) { control ->
            control.send("display-message")
            control.output(capacity = 8).first()
        }

        server.liveState(session, scope).first()
        server.withLiveState(session) { live -> live.first() }
    }
}
