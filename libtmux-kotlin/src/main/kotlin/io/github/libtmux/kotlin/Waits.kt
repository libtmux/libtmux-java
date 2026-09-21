package io.github.libtmux.kotlin

import io.github.libtmux.Channel
import io.github.libtmux.Pane
import io.github.libtmux.TextOutcome
import io.github.libtmux.WakeReason
import java.util.function.Predicate
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Waits until [text] shows in this pane.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout, and it
 * does not stop a program already running in the pane.
 *
 * @param timeout how long to keep looking, finite and not negative
 * @return why the wait ended
 */
public suspend fun Pane.awaitText(text: String, timeout: Duration): TextOutcome =
    interruptible { awaitText(text, timeout.toJavaDuration()) }

/**
 * As [awaitText], looking every [every] rather than every 50 ms.
 *
 * @param every how long to leave between looks, at least 10 ms
 */
public suspend fun Pane.awaitText(text: String, timeout: Duration, every: Duration): TextOutcome =
    interruptible { awaitText(text, timeout.toJavaDuration(), every.toJavaDuration()) }

/**
 * Waits until [settled] accepts this pane.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout. A read
 * already sent to tmux is not taken back.
 *
 * @param timeout how long to keep looking, finite and not negative
 * @return why the wait ended
 */
public suspend fun Pane.await(settled: Predicate<Pane>, timeout: Duration): WakeReason =
    interruptible { await(settled, timeout.toJavaDuration()) }

/**
 * As [await], looking every [every] rather than every 50 ms.
 *
 * @param every how long to leave between looks, at least 10 ms
 */
public suspend fun Pane.await(settled: Predicate<Pane>, timeout: Duration, every: Duration): WakeReason =
    interruptible { await(settled, timeout.toJavaDuration(), every.toJavaDuration()) }

/**
 * Waits for something to signal this channel.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout. A
 * `wait-for` tmux has already accepted is not taken back.
 *
 * @param timeout how long to wait, finite and positive
 * @return why the wait ended
 */
public suspend fun Channel.await(timeout: Duration): WakeReason =
    interruptible { await(timeout.toJavaDuration()) }

/**
 * As [await], while preserving process capacity for a call that signals it.
 *
 * @param timeout how long to wait, finite and positive
 */
public suspend fun Channel.awaitReservingCapacity(timeout: Duration): WakeReason =
    interruptible { awaitReservingCapacity(timeout.toJavaDuration()) }

private suspend fun <T> interruptible(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }
