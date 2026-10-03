package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.kotlin.*
import io.github.libtmux.kotlin.query.name
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** List all windows, then the windows captured for one session. */
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
                println("windows=" + server.windows().map { it.name }.sorted().joinToString(","))
                val session = server.session(Session.name eq "work-one")
                println("session-windows=" + session.windows.map { it.name }.sorted().joinToString(","))
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}
