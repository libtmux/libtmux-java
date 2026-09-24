package io.github.libtmux.kotlin

import io.github.libtmux.Channel
import io.github.libtmux.Pane
import io.github.libtmux.TextOutcome
import io.github.libtmux.WakeReason
import java.util.function.Predicate
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/*
 * Each wait blocks a thread of [dispatcher], Dispatchers.IO unless a caller passes its own, such as
 * a limitedParallelism view that bounds how many threads waits may hold. Cancelling a wait
 * interrupts that thread.
 */

/**
 * Waits until [text] shows in this pane.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout, and it
 * does not stop a program already running in the pane.
 *
 * @param timeout how long to keep looking, finite and not negative
 * @param dispatcher where the blocking wait runs
 * @return why the wait ended
 */
public suspend fun Pane.awaitText(
    text: String,
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): TextOutcome = runInterruptible(dispatcher) { awaitText(text, timeout.toJavaDuration()) }

/**
 * As [awaitText], looking every [every] rather than every 50 ms.
 *
 * @param every how long to leave between looks, at least 10 ms
 * @param dispatcher where the blocking wait runs
 */
public suspend fun Pane.awaitText(
    text: String,
    timeout: Duration,
    every: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): TextOutcome = runInterruptible(dispatcher) { awaitText(text, timeout.toJavaDuration(), every.toJavaDuration()) }

/**
 * Waits until [settled] accepts this pane.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout. A read
 * already sent to tmux is not taken back.
 *
 * @param timeout how long to keep looking, finite and not negative
 * @param dispatcher where the blocking wait runs
 * @return why the wait ended
 */
public suspend fun Pane.await(
    settled: Predicate<Pane>,
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): WakeReason = runInterruptible(dispatcher) { await(settled, timeout.toJavaDuration()) }

/**
 * As [await], looking every [every] rather than every 50 ms.
 *
 * @param every how long to leave between looks, at least 10 ms
 * @param dispatcher where the blocking wait runs
 */
public suspend fun Pane.await(
    settled: Predicate<Pane>,
    timeout: Duration,
    every: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): WakeReason = runInterruptible(dispatcher) { await(settled, timeout.toJavaDuration(), every.toJavaDuration()) }

/**
 * Waits for something to signal this channel.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout. A
 * `wait-for` tmux has already accepted is not taken back.
 *
 * @param timeout how long to wait, finite and positive
 * @param dispatcher where the blocking wait runs
 * @return why the wait ended
 */
public suspend fun Channel.await(
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): WakeReason = runInterruptible(dispatcher) { await(timeout.toJavaDuration()) }

/**
 * As [await], while preserving process capacity for a call that signals it.
 *
 * @param timeout how long to wait, finite and positive
 * @param dispatcher where the blocking wait runs
 */
public suspend fun Channel.awaitReservingCapacity(
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): WakeReason = runInterruptible(dispatcher) { awaitReservingCapacity(timeout.toJavaDuration()) }
