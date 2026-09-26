package io.github.libtmux.kotlin

import io.github.libtmux.ServerConfig
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Where a [Server]'s suspend surface runs, and how wide it is allowed to run.
 *
 * Two independently sized pools, not one: [commands] backs every `suspend` operation this module
 * generates or hand-writes, sized from the transport's own admission bound rather than a guess.
 * [streamReads] backs only what still genuinely blocks a thread — [Server.liveState]'s background
 * pump. [ControlClient.output] and [ControlClient.events] hold no thread from either pool; they are
 * built on [io.github.libtmux.control.EventSubscription.poll] and `onReady`, not on a blocking read.
 */
public class ExecutionPolicy(
    public val commands: CoroutineDispatcher,
    public val streamReads: CoroutineDispatcher,
    public val defaultDeadline: Duration = Duration.INFINITE,
) {
    public companion object {
        private const val DEFAULT_STREAM_READ_CAPACITY = 16

        /**
         * Sizes [commands] from `config`'s own [ServerConfig.maxConcurrentCommands], and
         * [streamReads] independently: it bounds a structurally different resource, open
         * [Server.liveState] watches, not tmux process admission.
         */
        public fun default(
            config: ServerConfig,
            streamReadCapacity: Int = DEFAULT_STREAM_READ_CAPACITY,
        ): ExecutionPolicy = ExecutionPolicy(
            commands = Dispatchers.IO.limitedParallelism(config.maxConcurrentCommands()),
            streamReads = Dispatchers.IO.limitedParallelism(streamReadCapacity),
        )
    }
}
