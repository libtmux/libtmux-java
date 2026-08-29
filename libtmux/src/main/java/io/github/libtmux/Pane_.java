package io.github.libtmux;

import io.github.libtmux.query.Fields;

/** Typed fields of {@link Pane}. */
public final class Pane_ {

    private static final Fields.TextField<Pane> ID =
            Fields.text("pane_id", pane -> pane.id().value());
    private static final Fields.TextField<Pane> COMMAND = Fields.text("pane_current_command", Pane::currentCommand);
    private static final Fields.NumberField<Pane> INDEX = Fields.number("pane_index", Pane::index);
    private static final Fields.FlagField<Pane> ACTIVE = Fields.flag("pane_active", Pane::active);

    private Pane_() {}

    /** The pane id. */
    public static Fields.TextField<Pane> id() {
        return ID;
    }

    /** The command tmux reported running in the pane. */
    public static Fields.TextField<Pane> command() {
        return COMMAND;
    }

    /** The pane's position in its window. */
    public static Fields.NumberField<Pane> index() {
        return INDEX;
    }

    /** Whether this was its window's active pane. */
    public static Fields.FlagField<Pane> active() {
        return ACTIVE;
    }
}
