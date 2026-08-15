package com.git_pull.libtmux;

import com.git_pull.libtmux.query.EntityMetamodel;
import com.git_pull.libtmux.query.Fields;

/** Typed fields of {@link Pane}. */
public final class Pane_ extends EntityMetamodel {

    private Pane_() {}

    /** The pane id. */
    public static Fields.TextField<Pane> id() {
        return text("pane_id", pane -> pane.id().value());
    }

    /** The command tmux reported running in the pane. */
    public static Fields.TextField<Pane> command() {
        return text("pane_current_command", Pane::currentCommand);
    }

    /** The pane's position in its window. */
    public static Fields.NumberField<Pane> index() {
        return number("pane_index", Pane::index);
    }

    /** Whether this was its window's active pane. */
    public static Fields.FlagField<Pane> active() {
        return flag("pane_active", Pane::active);
    }
}
