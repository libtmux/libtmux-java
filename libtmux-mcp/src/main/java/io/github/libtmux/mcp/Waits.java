package io.github.libtmux.mcp;

import java.time.Duration;

/**
 * How long a wait may last.
 *
 * <p>Every wait is bounded, and an over-large request is clamped rather than refused. The bound
 * protects the agent's turn, not the connection: a tool call that blocks forever gives a model no
 * way to change its mind, because MCP has no way to cancel a call it is inside. A ceiling makes
 * choosing the wrong thing to wait for cheap and repeatable instead of terminal.
 *
 * <p>The connection itself is never at risk. Measured against this SDK, one tool call blocking for
 * six seconds served twenty interleaved calls in the same window, because the SDK runs a synchronous
 * handler on {@code Schedulers.boundedElastic} rather than on the thread reading the transport.
 */
final class Waits {

    /** What a caller gets when it names no timeout: long enough for a test run, short enough to retry. */
    static final Duration DEFAULT = Duration.ofSeconds(30);

    /** The most any wait may last, whatever was asked for. */
    static final Duration CEILING = Duration.ofMinutes(2);

    /** How often a wait that has to look at the screen looks again. */
    static final Duration POLL = Duration.ofMillis(50);

    private Waits() {}

    /** The timeout a call asked for, brought inside the ceiling. */
    static Duration requested(Call call) {
        double seconds = call.number("timeout", DEFAULT.toMillis() / 1000.0);
        long millis = Math.round(seconds * 1000);
        return Duration.ofMillis(Math.clamp(millis, 100, CEILING.toMillis()));
    }

    static double asSeconds(Duration duration) {
        return Math.round(duration.toMillis() / 10.0) / 100.0;
    }
}
