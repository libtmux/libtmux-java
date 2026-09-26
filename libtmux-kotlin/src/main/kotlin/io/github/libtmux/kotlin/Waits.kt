package io.github.libtmux.kotlin

import io.github.libtmux.Channel
import io.github.libtmux.WakeReason
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/*
 * Channel is reused as-is (§2c): every other wrapper class's WAIT operations are hand-written members
 * — Pane.awaitText, Pane.await, Pane.run, Server.control — rather than living here, so this file only
 * covers what remains a Java type.
 */

/**
 * Waits for something to signal this channel.
 *
 * Cancelling the coroutine interrupts the wait. That is not a timeout. A `wait-for` tmux has already
 * accepted is not taken back.
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
