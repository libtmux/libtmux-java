package io.github.libtmux;

import io.github.libtmux.query.EntityMetamodel;
import io.github.libtmux.query.Fields;
import java.util.Optional;

/** Typed fields of {@link Window}. */
public final class Window_ extends EntityMetamodel {

    private Window_() {}

    /** The underlying window id, shared by every link to it. */
    public static Fields.TextField<Window> id() {
        return text("window_id", window -> window.id().value());
    }

    /** The window name. */
    public static Fields.TextField<Window> name() {
        return text("window_name", Window::name);
    }

    /** Where this link sits in its session. */
    public static Fields.NumberField<Window> index() {
        return number("window_index", window -> window.index().value());
    }

    /** Whether this was its session's active window. */
    public static Fields.FlagField<Window> active() {
        return flag("window_active", Window::active);
    }

    /** Whether the underlying window is linked into more than one session. */
    public static Fields.FlagField<Window> linked() {
        return flag("window_linked", Window::linked);
    }

    /** This link's panes. */
    public static Fields.ToManyRef<Window, Pane> panes() {
        return toMany("panes", Window::panes);
    }

    /** The session this link belongs to. */
    public static Fields.ToOneRef<Window, Session> session() {
        return toOne("session", window -> Optional.of(window.session()));
    }
}
