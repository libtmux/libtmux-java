package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerEndpoint
import io.github.libtmux.kotlin.*
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** List the sessions returned by a server read. */
fun main(args: Array<String>) {
    try {
        execute(args)
    } catch (error: Exception) {
        if (error is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}

private fun execute(args: Array<String>) {
    require(args.size == 3) { "expected: tmux-binary socket-path config-file" }
    val config = ServerConfig.builder()
        .binary(args[0])
        .endpoint(ServerEndpoint.socketPath(Path.of(args[1])))
        .configFile(Path.of(args[2]))
        .build()
    runBlocking {
        withServer(config) { server ->
            val names = server.sessions().map { it.name }.sorted()
            println("sessions=" + names.joinToString(","))
        }
    }
}
