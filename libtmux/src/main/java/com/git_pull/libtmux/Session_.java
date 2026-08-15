package com.git_pull.libtmux;

import com.git_pull.libtmux.query.EntityMetamodel;
import com.git_pull.libtmux.query.Fields;

/**
 * Typed fields of {@link Session}, for building an expression that can be read as well as run.
 *
 * <p>Each field exposes only the operators its type supports, so asking a flag to start with a
 * string does not compile. Every field is canonical: it names a tmux format, which is what lets a
 * later backend translate the same expression into tmux's own {@code -f} filter.
 */
public final class Session_ extends EntityMetamodel {

    private Session_() {}

    /** The session id, as text, so it can be compared and listed. */
    public static Fields.TextField<Session> id() {
        return text("session_id", session -> session.id().value());
    }

    /** The session name. */
    public static Fields.TextField<Session> name() {
        return text("session_name", Session::name);
    }

    /** Whether a client was attached when this was captured. */
    public static Fields.FlagField<Session> attached() {
        return flag("session_attached", Session::attached);
    }

    /** This session's windows. */
    public static Fields.ToManyRef<Session, Window> windows() {
        return toMany("windows", Session::windows);
    }
}
