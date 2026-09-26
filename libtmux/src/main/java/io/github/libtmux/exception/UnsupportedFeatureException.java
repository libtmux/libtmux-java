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
 * <p>The feature and the versions are in the message and not in accessors: a throwable is
 * serializable, so exposing a {@link TmuxVersion} here would mean making that serializable too.
 */
public final class UnsupportedFeatureException extends LibTmuxException {
    private static final long serialVersionUID = 1L;

    /** A feature that needs {@code required} or newer, on a server running {@code running}. */
    public UnsupportedFeatureException(String feature, TmuxVersion required, TmuxVersion running) {
        super(feature + " requires tmux " + required + ", but this server runs " + running, null);
    }

    /**
     * For a capability that is missing from a range rather than from everything before a release.
     *
     * <p>Not every gap is a floor: {@code run-shell} reports its command's output on 3.2a, loses it
     * in 3.3a and 3.4, and reports it again from 3.5. "Requires 3.5" would be a lie to a 3.2a
     * caller, for whom it works.
     */
    public UnsupportedFeatureException(String message) {
        super(message, null);
    }
}
