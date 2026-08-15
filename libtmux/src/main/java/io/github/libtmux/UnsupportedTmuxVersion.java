package io.github.libtmux;

/**
 * The running tmux does not have something the call asked for.
 *
 * <p>Raised instead of dropping the option. The Python sibling warns and carries on, which returns a
 * pane that differs from the one described in a way nothing downstream can detect; a caller who
 * asked for an empty pane and got a shell has no way to notice before something runs in it.
 *
 * <p>Its own type so a caller who can do without the feature can catch exactly this, rather than
 * matching on a message. The feature and the versions are in the message and not in accessors: a
 * throwable is serializable, so exposing a {@link TmuxVersion} here would mean making that
 * serializable too, and no caller has needed to read them apart from the text.
 */
public final class UnsupportedTmuxVersion extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    UnsupportedTmuxVersion(String feature, TmuxVersion required, TmuxVersion running) {
        super(feature + " requires tmux " + required + ", but this server runs " + running);
    }

    /**
     * For a capability that is missing from a range rather than from everything before a release.
     *
     * <p>Not every gap is a floor: {@code run-shell} reports its command's output on 3.2a, loses it
     * in 3.3a and 3.4, and reports it again from 3.5. "Requires 3.5" would be a lie to a 3.2a
     * caller, for whom it works.
     */
    UnsupportedTmuxVersion(String message) {
        super(message);
    }
}
