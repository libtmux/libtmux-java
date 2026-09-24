// Compiled by the oldest Kotlin this module claims, against the jar it publishes, by the
// compileOldestConsumer task. Every public declaration is reached, so a module a Kotlin 2.1
// compiler cannot read fails to compile here.
package consumer

import io.github.libtmux.Channel
import io.github.libtmux.Pane
import io.github.libtmux.Pane_
import io.github.libtmux.Server
import io.github.libtmux.control.EventSubscription
import io.github.libtmux.control.PaneOutput
import io.github.libtmux.kotlin.activePaneOrNull
import io.github.libtmux.kotlin.activeWindowOrNull
import io.github.libtmux.kotlin.await
import io.github.libtmux.kotlin.awaitDelivery
import io.github.libtmux.kotlin.awaitText
import io.github.libtmux.kotlin.deliveries
import io.github.libtmux.kotlin.filter
import io.github.libtmux.kotlin.getOrNull
import io.github.libtmux.kotlin.kept
import io.github.libtmux.kotlin.not
import io.github.libtmux.kotlin.orNull
import io.github.libtmux.kotlin.paneOrNull
import io.github.libtmux.kotlin.readOnly
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

suspend fun reachEverything(server: Server, pane: Pane, channel: Channel, output: EventSubscription<PaneOutput>) {
    val session = server.sessions().readOnly().first()
    session.activeWindowOrNull()?.activePaneOrNull()
    server.paneOrNull(pane.id())
    server.options().getOrNull("status")
    server.environment().getOrNull("HOME")
    pane.pid().orNull()
    server.panes().filter(!Pane_.command().`is`("vim"))
    pane.awaitText("ready", 1.seconds, Dispatchers.IO)
    pane.await({ true }, 1.seconds)
    channel.await(1.seconds)
    output.awaitDelivery(1.seconds)?.kept()
    output.deliveries(Dispatchers.IO).map { it.kept().data() }
}
