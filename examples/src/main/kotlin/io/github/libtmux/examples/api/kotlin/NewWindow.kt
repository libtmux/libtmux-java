package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.WindowSpec
import io.github.libtmux.kotlin.*
import io.github.libtmux.kotlin.query.name
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** Create a window and read the updated session. */
fun main(args: Array<String>) {
    try {
        require(args.size == 3) { "expected: tmux-binary socket-path config-file" }
        val config = ServerConfig.builder()
            .binary(args[0])
            .endpoint(ServerEndpoint.socketPath(Path.of(args[1])))
            .configFile(Path.of(args[2]))
            .build()
        runBlocking {
            withServer(config) { server ->
                val session = server.session(Session.name eq "work-one")
                val window = session.newWindow(
                    WindowSpec.builder().named("api-window").running("/bin/cat").build()
                )
                println("created=${window.name}")
                val refreshed = server.session(Session.name eq "work-one")
                println("windows=" + refreshed.windows.map { it.name }.sorted().joinToString(","))
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}
