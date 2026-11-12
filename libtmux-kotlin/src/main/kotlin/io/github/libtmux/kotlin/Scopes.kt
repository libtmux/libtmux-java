package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import io.github.libtmux.ServerLaunch
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Opens a [Server], runs [block], and closes it — even if [block] throws or is cancelled.
 *
 * The rule this follows, stated once: the scope that acquired a Java `AutoCloseable` is the only
 * thing that closes it. [close][Server.close] runs with [NonCancellable] so a cancelled [block]
 * still releases the transport's threads.
 */
public suspend fun <R> withServer(
    config: ServerConfig,
    policy: ExecutionPolicy = ExecutionPolicy.default(config),
    block: suspend (Server) -> R,
): R {
    val server = Server.open(config, policy)
    try {
        return block(server)
    } finally {
        withContext(NonCancellable + policy.commands) { server.close() }
    }
}

/**
 * Starts a private tmux server, runs [block], and ends the server — even if [block] throws or is
 * cancelled. Closing it removes the server's directory and throws if the server still answers.
 */
public suspend fun <R> withPrivateServer(
    launch: ServerLaunch = ServerLaunch.builder().build(),
    policy: ExecutionPolicy = ExecutionPolicy.default(ServerConfig.builder().build()),
    block: suspend (Server) -> R,
): R {
    val server = Server.start(launch, policy)
    try {
        return block(server)
    } finally {
        withContext(NonCancellable + policy.commands) { server.close() }
    }
}

/** As [withServer], for a [ControlClient] attached through [server]. */
public suspend fun <R> withControl(
    server: Server,
    session: Session,
    timeout: Duration = server.config.defaultTimeout().toKotlinDuration(),
    block: suspend (ControlClient) -> R,
): R {
    val control = server.control(session, timeout)
    try {
        return block(control)
    } finally {
        withContext(NonCancellable + server.policy.commands) { control.close() }
    }
}
