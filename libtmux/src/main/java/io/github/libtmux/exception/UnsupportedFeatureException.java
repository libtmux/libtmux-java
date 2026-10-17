package io.github.libtmux.exception;

import io.github.libtmux.TmuxVersion;

/**
 * The running tmux does not have something the call asked for.
 *
 * <p>Raised instead of dropping the option. The Python sibling warns and carries on, which returns a
 * pane that differs from the one described in a way nothing downstream can detect; a caller who
 * asked for an empty pane and got a shell has no way to notice before something runs in it. Skip the
 * feature, or require a newer tmux.
 *
 * <p>The same type reports a tmux older than {@link TmuxVersion#MINIMUM}, which no call can do
 * without. Test {@link #belowFloor()} before treating a failure as an optional feature that can be
 * skipped.
 *
 * <p>The feature and the versions are in the message and not in accessors: a throwable is
 * serializable, so exposing a {@link TmuxVersion} here would mean making that serializable too.
 */
public final class UnsupportedFeatureException extends LibTmuxException {
    private static final long serialVersionUID = 1L;

    private final boolean belowFloor;

    /** A feature that needs {@code required} or newer, on a server running {@code running}. */
    public UnsupportedFeatureException(String feature, TmuxVersion required, TmuxVersion running) {
        super(feature + " requires tmux " + required + ", but this server runs " + running, null);
        this.belowFloor = false;
    }

    /**
     * For a capability that is missing from a range rather than from everything before a release.
     *
     * <p>Not every gap is a floor: {@code run-shell} loses its command's output in 3.3a and 3.4 and
     * reports it again from 3.5. "Requires 3.5" would be a lie to a 3.3a caller, for whom the
     * command runs.
     */
    public UnsupportedFeatureException(String message) {
        super(message, null);
        this.belowFloor = false;
    }

    private UnsupportedFeatureException(String message, boolean belowFloor) {
        super(message, null);
        this.belowFloor = belowFloor;
    }

    /**
     * A tmux older than {@link TmuxVersion#MINIMUM}, which this library does not support at all.
     *
     * @param message what was found and what to do about it
     */
    public static UnsupportedFeatureException tmuxBelowFloor(String message) {
        return new UnsupportedFeatureException(message, true);
    }

    /**
     * Whether the tmux is older than {@link TmuxVersion#MINIMUM}, so that nothing this library does
     * can work on it, as opposed to one optional feature being missing from a tmux that is otherwise
     * supported. Upgrade tmux, or use an earlier libtmux release; there is nothing to do without.
     */
    public boolean belowFloor() {
        return belowFloor;
    }
}
