package io.github.libtmux.mcp;

import io.github.libtmux.WakeReason;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Waiting on tmux's own synchronisation channel.
 *
 * <p>The only wait that infers nothing. tmux blocks server-side and returns when something signals
 * the channel, so one tmux process covers the whole wait however long it is, and the answer does not
 * depend on what the screen happened to look like.
 *
 * <p>It has two traps, and both are silent. A successful exit does not mean a signal — killing the
 * server under a waiter exits successfully too — which is why the answer here is a reason rather
 * than a boolean. And a signal outlives the moment it was sent: signalling a channel nobody is
 * waiting on is remembered and satisfies the next wait, whenever that comes.
 */
final class Channels {

    private Channels() {}

    record Woke(
            String channel,
            String outcome,
            double seconds,
            double effectiveTimeout,
            @Nullable String note) {}

    record Signalled(String channel, String note) {}

    record Drained(String channel, boolean hadSignal, String note) {}

    static Woke waitFor(Call call) {
        String channel = call.string("channel");
        Duration timeout = Waits.requested(call);
        boolean drained = call.flag("drain_first", false);
        if (drained) {
            call.server().drain(channel);
        }
        long started = System.nanoTime();
        WakeReason wake = call.server().waitFor(channel, timeout);
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        return new Woke(
                channel, wake.name(), Math.round(seconds * 100) / 100.0, Waits.asSeconds(timeout), note(wake, drained));
    }

    /**
     * Whether a signal was a stale one cannot be told from how long the wait took — a real signal can
     * arrive in a millisecond. It can be told from whether the caller started from a known state.
     */
    private static @Nullable String note(WakeReason wake, boolean drained) {
        return switch (wake) {
            case SIGNALLED ->
                drained
                        ? null
                        : "The channel was not drained first, so this may have been satisfied by a signal sent "
                                + "before the call. Pass drain_first=true when the channel's history is not yours.";
            case TIMED_OUT ->
                "Nothing signalled the channel. Check that the command really appends "
                        + "'; tmux wait-for -S <channel>' — a command that failed before reaching it never signals.";
            case SERVER_GONE ->
                "The tmux server ended while waiting. tmux reports that as a successful "
                        + "wake, so nothing this wait was guarding can be relied on.";
        };
    }

    static Signalled signal(Call call) {
        String channel = call.string("channel");
        call.server().signal(channel);
        return new Signalled(
                channel,
                "Signalled. If nothing was waiting, tmux remembers it and the next wait on this channel "
                        + "returns immediately.");
    }

    static Drained drain(Call call) {
        String channel = call.string("channel");
        boolean had = call.server().drain(channel);
        return new Drained(
                channel,
                had,
                had
                        ? "A signal was waiting and has been consumed; a wait now starts from a known state."
                        : "Nothing was waiting on it.");
    }
}
