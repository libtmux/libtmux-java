package io.github.libtmux.examples.api.kotlin

import io.github.libtmux.kotlin.*
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/** Connect to a private tmux server. */
fun main() {
    try {
        runBlocking {
            withPrivateServer { server ->
                println("connected=${server.isAlive()}")
            }
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("Example failed: ${error.message}")
        exitProcess(1)
    }
}
