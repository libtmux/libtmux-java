package io.github.libtmux;

import java.time.Duration;

/**
 * Why a wait for text on a pane ended.
 *
 * <p>Separate from {@link WakeReason}, which answers for a tmux channel, because this wait reads a
 * screen rather than blocking on a signal and so can end a way a channel never does: with the text
 * already there. A pane that has said {@code ready} since an hour ago must not answer a wait for
 * {@code ready} the way output that just arrived does, and it must not be reported as a timeout
 * either — a caller told only "not found" would go looking for text that is in plain sight.
 *
 * <p>{@link Pane#await(java.util.function.Predicate, Duration)} keeps {@link WakeReason}: a caller
 * wrote that condition and can test it before waiting, so the library is not the only thing that can
 * see it was already true.
 */
public enum TextOutcome {
    /** The text appeared while the wait was running. */
    APPEARED,
    /**
     * The first look already showed the text, so this wait did not see it arrive.
     *
     * <p>Exactly that, and no more. It may be output from a command the caller started a moment ago
     * and that finished before the first read, or it may have been on the pane for an hour — a
     * screen cannot tell those apart, and reporting them as the same thing would be the claim this
     * outcome exists to avoid. A caller that needs the difference should not be reading a screen:
     * append {@code ; tmux wait-for -S name} to its own command and block on {@link Server#channel},
     * which is exact.
     *
     * <p>An echo of what this library typed is never what is found here; that is the caller's own
     * question coming back, and it is discounted.
     */
    PRESENT_AT_ENTRY,
    /** The deadline passed with the text absent. */
    TIMED_OUT,
    /** The server went away underneath the wait, so nothing it was guarding can be relied on. */
    SERVER_GONE
}
