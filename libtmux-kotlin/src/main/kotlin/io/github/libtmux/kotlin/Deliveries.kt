package io.github.libtmux.kotlin

import io.github.libtmux.control.Delivery
import io.github.libtmux.control.EventSubscription
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible

/**
 * Reads this subscription until it ends, then closes it.
 *
 * A [Delivery.Gap] is emitted where the buffer discarded events, ahead of the
 * events that remain. Cancelling the collection closes the subscription and
 * drops steps not yet collected. Closing does not reconnect, and it does not
 * undo a command tmux has already accepted.
 *
 * The flow fails with the subscription's cause when the control client ends
 * it. A caller who closed it gets a normal completion. Collect once: a later
 * collection finds the subscription closed.
 */
/** The event a strict reader kept. A gap fails the read. */
public fun <T : Any> Delivery<T>.kept(): T = Delivery.kept(this)

public fun <T : Any> EventSubscription<T>.deliveries(): Flow<Delivery<T>> {
    val subscription = this
    return flow {
        try {
            while (true) {
                val delivered = runInterruptible { subscription.next() }
                if (delivered.isEmpty) {
                    val failure = subscription.cause()
                    if (failure.isPresent) {
                        throw failure.get()
                    }
                    break
                }
                emit(delivered.get())
            }
        } finally {
            subscription.close()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Waits up to [timeout] for the next step.
 *
 * Cancelling this wait interrupts it and leaves the subscription open. A zero
 * timeout reads the buffer without waiting. [Duration.INFINITE] waits until a
 * step arrives or the subscription ends.
 *
 * @param timeout how long to wait
 * @return the next gap or event, or null when none arrived before the deadline.
 * A client that ended the subscription fails this wait with that cause.
 * @throws IllegalArgumentException if [timeout] is negative
 */
public suspend fun <T : Any> EventSubscription<T>.awaitDelivery(timeout: Duration): Delivery<T>? =
    runInterruptible(Dispatchers.IO) {
        val delivered =
            if (timeout == Duration.INFINITE) {
                next()
            } else {
                next(timeout.toJavaDuration())
            }
        if (delivered.isPresent) {
            delivered.get()
        } else {
            val failure = cause()
            if (failure.isPresent) {
                throw failure.get()
            }
            null
        }
    }
