package io.github.libtmux.kotlin

import io.github.libtmux.control.Delivery
import io.github.libtmux.control.EventSubscription
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridges an [EventSubscription] into a cold [Flow], over the non-blocking
 * [EventSubscription.poll]/[EventSubscription.onReady] path: no thread is parked per subscription.
 *
 * `open()` runs once per [kotlinx.coroutines.flow.Flow.collect], so two concurrent collections each
 * get their own subscription with their own buffer and gap accounting: fanning out means
 * subscribing twice.
 *
 * This is a plain `flow {}`, deliberately not `callbackFlow`. Every [EventSubscription.poll] runs on
 * the collector's own thread, so delivery order is the subscription's order, and `emit` suspends
 * while the collector is busy: overflow lands in the subscription's own drop-oldest buffer and
 * surfaces as a [Delivery.Gap], never as silent loss in an intermediate channel. A `callbackFlow`
 * built the same way — the readiness callback draining `poll()` straight into the channel — lost
 * 240,892 of 300,000 events with no `Gap` recorded in a stress probe (`FlowBridgeStressTest`):
 * `trySend` failing on a full channel silently discarded the polled item. This bridge cannot lose
 * that way, because nothing but the collector itself ever calls `poll()`.
 */
internal fun <T : Any> coldFlowFrom(
    open: () -> EventSubscription<T>,
    onSubscribed: suspend () -> Unit = {},
): Flow<Delivery<T>> = flow {
    open().use { subscription ->
        // After the subscription exists and before the first read: whatever this triggers lands in
        // the buffer rather than before it.
        onSubscribed()
        while (true) {
            val next = subscription.poll()
            when {
                next.isPresent -> emit(next.get())
                subscription.isClosed -> {
                    subscription.cause().ifPresent { throw it }
                    return@flow
                }
                else -> suspendCancellableCoroutine { waiter ->
                    subscription.onReady { waiter.resume(Unit) }
                    waiter.invokeOnCancellation { subscription.clearReady() }
                }
            }
        }
    }
}
