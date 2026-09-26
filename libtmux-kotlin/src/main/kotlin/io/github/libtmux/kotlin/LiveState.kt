package io.github.libtmux.kotlin

import io.github.libtmux.snapshot.ServerMirror
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * A live, continuously rebuilt view of [server], as a [StateFlow].
 *
 * Wraps Java's [ServerMirror] rather than reinventing it: every routine case — resnapshot on
 * notification, on gap, and on reconnect; conflation while a rebuild is in flight; ending once
 * [session] itself is gone — is already handled once, correctly, inside [ServerMirror]. This
 * function's only job is pumping [ServerMirror.awaitNewer] on a dedicated thread from
 * [ExecutionPolicy.streamReads] and publishing what comes back. Named `liveState`, not `ServerMirror`,
 * so it does not collide with the Java type it wraps.
 *
 * [scope] owns the returned [StateFlow]'s whole lifetime: closing the mirror is this function's own
 * `launch`+`finally`, tied to [scope]'s cancellation, so the caller never has to close it by hand.
 *
 * @param attachTimeout how long to wait for the first attach, and the deadline each later rebuild
 *     wait uses to recheck [scope] for cancellation
 * @throws io.github.libtmux.exception.TargetGoneException synchronously if [session]'s server has
 *     already gone when this is called; later, the same failure ends [scope] instead, since it
 *     surfaces from the launched pump rather than from this call
 */
public suspend fun Server.liveState(
    session: Session,
    scope: CoroutineScope,
    attachTimeout: Duration = config.defaultTimeout().toKotlinDuration(),
): StateFlow<ServerMirror.View> {
    val mirror = runInterruptible(policy.streamReads) {
        ServerMirror.open(session.java, attachTimeout.toJavaDuration())
    }
    val state = MutableStateFlow(mirror.current())
    scope.launch(policy.streamReads) {
        try {
            while (isActive) {
                val newer = runInterruptible(policy.streamReads) {
                    mirror.awaitNewer(state.value.epoch(), attachTimeout.toJavaDuration())
                }
                if (newer.isPresent) {
                    state.value = newer.get()
                    continue
                }
                if (mirror.isEnded()) {
                    // A routine reattach after ControlEndedException, a Gap resnapshot, and every
                    // other ordinary case already resolved inside ServerMirror itself; reaching here
                    // means it ended terminally, so the failure that says why propagates and, by
                    // structured concurrency, cancels scope — not a routine condition to swallow.
                    mirror.cause().ifPresent { throw it }
                    return@launch
                }
                // Quiet for a whole attachTimeout with the mirror still open: loop, so isActive is
                // rechecked. runInterruptible already responds to cancellation before this point.
            }
        } finally {
            withContext(NonCancellable + policy.streamReads) { mirror.close() }
        }
    }
    return state.asStateFlow()
}

/**
 * As [liveState], scoped to [block]: opens the live view, runs [block] against it, and cancels the
 * background pump before returning — the same `withServer`/`withControl` shape, for the one resource
 * [liveState] itself cannot close on its own.
 *
 * [liveState]'s pump runs until [scope] ends, by design (a caller collecting it for the life of a
 * program passes its own long-lived scope) — which means a bare `coroutineScope { liveState(...) }`
 * never returns: `coroutineScope` waits for every child, the pump included, and nothing inside it
 * asks the pump to stop. This cancels that one child explicitly once [block] is done, whether it
 * returns or throws.
 */
public suspend fun <R> Server.withLiveState(
    session: Session,
    attachTimeout: Duration = config.defaultTimeout().toKotlinDuration(),
    block: suspend (StateFlow<ServerMirror.View>) -> R,
): R = coroutineScope {
    val live = liveState(session, this, attachTimeout)
    try {
        block(live)
    } finally {
        coroutineContext.cancelChildren()
    }
}
