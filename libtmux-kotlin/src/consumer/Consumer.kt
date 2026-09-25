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
import io.github.libtmux.kotlin.control
import io.github.libtmux.kotlin.deliveries
import io.github.libtmux.kotlin.filter
import io.github.libtmux.kotlin.getOrNull
import io.github.libtmux.kotlin.kept
import io.github.libtmux.kotlin.not
import io.github.libtmux.kotlin.orNull
import io.github.libtmux.kotlin.paneOrNull
import io.github.libtmux.kotlin.run
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

suspend fun reachEverything(server: Server, pane: Pane, channel: Channel, output: EventSubscription<PaneOutput>) {
    val session = readOnly(server.sessions()).first()
    readOnly(server.paneFields(listOf("pane_tty")))
    session.activeWindowOrNull()?.activePaneOrNull()
    server.paneOrNull(pane.id())
    server.options().getOrNull("status")
    server.environment().getOrNull("HOME")
    pane.pid().orNull()
    server.panes().filter(!Pane_.command().`is`("vim"))
    pane.awaitText("ready", 1.seconds, Dispatchers.IO)
    pane.await({ true }, 1.seconds)
    pane.run("true", 1.seconds)
    channel.await(1.seconds)
    server.control(session, 1.seconds)
    output.awaitDelivery(1.seconds)?.kept()
    output.deliveries(Dispatchers.IO).map { it.kept().data() }
}

// A collection Kotlin reads as mutable resolves to the overload that fails the build.
private fun <T> readOnly(list: List<T>): List<T> = list

@Deprecated("a core list reached Kotlin as mutable", level = DeprecationLevel.ERROR)
@JvmName("refuseMutableList")
private fun <T> readOnly(list: MutableList<T>): List<T> = list

private fun <K, V> readOnly(map: Map<K, V>): Map<K, V> = map

@Deprecated("a core map reached Kotlin as mutable", level = DeprecationLevel.ERROR)
@JvmName("refuseMutableMap")
private fun <K, V> readOnly(map: MutableMap<K, V>): Map<K, V> = map
