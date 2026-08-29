package io.github.libtmux;

import io.github.libtmux.query.Fields;
import java.util.Optional;

/** Typed fields of {@link Window}. */
public final class Window_ {

    private static final Fields.TextField<Window> ID =
            Fields.text("window_id", window -> window.id().value());
    private static final Fields.TextField<Window> NAME = Fields.text("window_name", Window::name);
    private static final Fields.NumberField<Window> INDEX =
            Fields.number("window_index", window -> window.index().value());
    private static final Fields.FlagField<Window> ACTIVE = Fields.flag("window_active", Window::active);
    private static final Fields.FlagField<Window> LINKED = Fields.flag("window_linked", Window::linked);
    private static final Fields.ToManyRef<Window, Pane> PANES = Fields.toMany("panes", Window::panes);
    private static final Fields.ToOneRef<Window, Session> SESSION =
            Fields.toOne("session", window -> Optional.of(window.session()));

    private Window_() {}

    /** The underlying window id, shared by every link to it. */
    public static Fields.TextField<Window> id() {
        return ID;
    }

    /** The window name. */
    public static Fields.TextField<Window> name() {
        return NAME;
    }

    /** Where this link sits in its session. */
    public static Fields.NumberField<Window> index() {
        return INDEX;
    }

    /** Whether this was its session's active window. */
    public static Fields.FlagField<Window> active() {
        return ACTIVE;
    }

    /** Whether the underlying window is linked into more than one session. */
    public static Fields.FlagField<Window> linked() {
        return LINKED;
    }

    /** This link's panes. */
    public static Fields.ToManyRef<Window, Pane> panes() {
        return PANES;
    }

    /** The session this link belongs to. */
    public static Fields.ToOneRef<Window, Session> session() {
        return SESSION;
    }
}
