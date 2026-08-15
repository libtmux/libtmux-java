package io.github.libtmux.jackson;

import io.github.libtmux.Client;
import io.github.libtmux.Client_;
import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;

/**
 * The wire ids for this library's own entities.
 *
 * <p>The ids are tmux's format names, which is what makes a document readable by something that is
 * not this library: another implementation already knows what {@code pane_current_command} means.
 */
public final class LibTmuxModels {

    private static final FilterModel<Pane> PANE = FilterModel.<Pane>named("pane")
            .field(Pane_.id())
            .field(Pane_.command())
            .field(Pane_.index())
            .field(Pane_.active())
            .build();

    private static final FilterModel<Window> WINDOW = FilterModel.<Window>named("window")
            .field(Window_.id())
            .field(Window_.name())
            .field(Window_.index())
            .field(Window_.active())
            .field(Window_.linked())
            .toMany(Window_.panes(), PANE)
            .build();

    private static final FilterModel<Session> SESSION = FilterModel.<Session>named("session")
            .field(Session_.id())
            .field(Session_.name())
            .field(Session_.attached())
            .toMany(Session_.windows(), WINDOW)
            .build();

    private static final FilterModel<Client> CLIENT = FilterModel.<Client>named("client")
            .field(Client_.name())
            .toOne(Client_.session(), SESSION)
            .build();

    private LibTmuxModels() {}

    /** The model documents name {@code pane}. */
    public static FilterModel<Pane> pane() {
        return PANE;
    }

    /** The model documents name {@code window}, reaching panes. */
    public static FilterModel<Window> window() {
        return WINDOW;
    }

    /** The model documents name {@code session}, reaching windows. */
    public static FilterModel<Session> session() {
        return SESSION;
    }

    /** The model documents name {@code client}, reaching its session. */
    public static FilterModel<Client> client() {
        return CLIENT;
    }
}
