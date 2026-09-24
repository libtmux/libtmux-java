package io.github.libtmux.kotlin

import io.github.libtmux.control.Delivery
import io.github.libtmux.control.EventSubscription
import java.util.Collections
import java.util.WeakHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible

/** The event a strict reader kept. A gap fails the read. */
public fun <T : Any> Delivery<T>.kept(): T = Delivery.kept(this)

/**
 * Reads this subscription until it ends, then closes it.
 *
 * A [Delivery.Gap] is emitted where the buffer discarded events, ahead of the
 * events that remain. Cancelling the collection closes the subscription and
 * drops steps not yet collected. Closing does not reconnect, and it does not
 * undo a command tmux has already accepted.
 *
 * The flow fails with the subscription's cause when the control client ends
 * it. A caller who closed it gets a normal completion. A subscription is
 * collected once: any later or concurrent collection, through this flow or
 * another `deliveries()` call, fails with [IllegalStateException] rather than
 * sharing or silently missing its steps.
 *
 * Each read runs on [Dispatchers.IO] and the flow adds no buffer of its own, so
 * events that arrive while the collector is busy wait in the subscription, and
 * its capacity alone decides what becomes a gap.
 */
public fun <T : Any> EventSubscription<T>.deliveries(): Flow<Delivery<T>> {
    val subscription = this
    return flow {
        check(collected.add(subscription)) {
            "this subscription was already collected; attach again for a new one"
        }
        try {
            while (true) {
                val delivered = runInterruptible(Dispatchers.IO) { subscription.next() }
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
    }
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

// Subscriptions whose deliveries have been claimed, weakly, so an abandoned one
// is not kept alive. Identity: EventSubscription does not override equals.
private val collected: MutableSet<EventSubscription<*>> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
