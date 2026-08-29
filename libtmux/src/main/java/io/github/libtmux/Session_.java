package io.github.libtmux;

import io.github.libtmux.query.Fields;

/**
 * Typed fields of {@link Session}, for building an expression that can be read as well as run.
 *
 * <p>Each field exposes only the operators its type supports, so asking a flag to start with a
 * string does not compile. Every field names a tmux format, so a backend model can bind the handle
 * before translating the expression into tmux's own {@code -f} filter.
 */
public final class Session_ {

    private static final Fields.TextField<Session> ID =
            Fields.text("session_id", session -> session.id().value());
    private static final Fields.TextField<Session> NAME = Fields.text("session_name", Session::name);
    private static final Fields.FlagField<Session> ATTACHED = Fields.flag("session_attached", Session::attached);
    private static final Fields.ToManyRef<Session, Window> WINDOWS = Fields.toMany("windows", Session::windows);

    private Session_() {}

    /** The session id, as text, so it can be compared and listed. */
    public static Fields.TextField<Session> id() {
        return ID;
    }

    /** The session name. */
    public static Fields.TextField<Session> name() {
        return NAME;
    }

    /** Whether a client was attached when this was captured. */
    public static Fields.FlagField<Session> attached() {
        return ATTACHED;
    }

    /** This session's windows. */
    public static Fields.ToManyRef<Session, Window> windows() {
        return WINDOWS;
    }
}
