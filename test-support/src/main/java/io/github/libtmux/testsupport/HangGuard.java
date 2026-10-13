package io.github.libtmux.testsupport;

import java.time.Duration;

/**
 * The one bound every test puts on waiting for something that is expected to happen.
 *
 * <p>A wait for a positive event returns the moment the event does, so this is spent only when a
 * test hangs. It is sized for a loaded hosted macOS runner, where starting a process or a shell is
 * several times slower than on Linux. A test that asserts a deadline, or that something does not
 * happen, keeps its own short bound instead.
 */
public final class HangGuard {

    /** The bound, in whole seconds. */
    public static final int SECONDS = 30;

    /** The bound, in milliseconds. */
    public static final long MILLIS = SECONDS * 1_000L;

    /** The bound as a duration. */
    public static final Duration DURATION = Duration.ofSeconds(SECONDS);

    private HangGuard() {}
}
