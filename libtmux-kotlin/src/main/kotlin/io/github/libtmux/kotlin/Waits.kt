package io.github.libtmux.kotlin

import io.github.libtmux.exception.LibTmuxException
import io.github.libtmux.Channel
import io.github.libtmux.Pane
import io.github.libtmux.PaneRun
import io.github.libtmux.Server
import io.github.libtmux.Session
import io.github.libtmux.TextOutcome
import io.github.libtmux.WakeReason
import io.github.libtmux.control.ControlClient
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
 * Runs a shell command in this pane to its end, and answers with its exit status and output.
 *
 * Cancelling the coroutine interrupts the wait. The command keeps running in
 * the pane; only the wait for its end is what cancellation reaches.
 *
 * @param command a line of shell, run as `eval` would run it
 * @param timeout how long to wait for it to end
 * @param dispatcher where the blocking wait runs
 * @return the command's exit status and what it printed
 */
public suspend fun Pane.run(
    command: String,
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): PaneRun = runInterruptible(dispatcher) { run(command, timeout.toJavaDuration()) }

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

/**
 * Attaches a control client to this session's server, and only to the process this
 * capture named — suspending rather than blocking while it becomes ready.
 *
 * Cancelling the coroutine interrupts that wait and ends the tmux client process
 * started for it. [Server.control] reports an interrupted wait as a plain
 * [LibTmuxException] rather than `InterruptedException`, which [runInterruptible]
 * would otherwise need to turn cancellation into `CancellationException`; this
 * re-throws that failure as `InterruptedException` itself when the thread is still
 * marked interrupted, so a cancelled attach completes the same way every other wait
 * in this module does.
 *
 * @param timeout how long to wait for the client to become ready
 * @param dispatcher where the blocking attach runs
 */
public suspend fun Server.control(
    session: Session,
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ControlClient =
    runInterruptible(dispatcher) {
        try {
            control(session, timeout.toJavaDuration())
        } catch (failure: LibTmuxException) {
            if (Thread.interrupted()) {
                throw InterruptedException("interrupted while attaching a control client").apply { initCause(failure) }
            }
            throw failure
        }
    }
