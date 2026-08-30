package io.github.libtmux.mcp;

import java.time.Duration;

/** Bounds a wait because SDK 2.0.1 does not cancel a running synchronous handler. */
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
