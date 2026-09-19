package io.github.libtmux;

import io.github.libtmux.query.Fields;

/** Typed fields of {@link Pane}. */
public final class Pane_ {

    private static final Fields.TextField<Pane> ID =
            Fields.text("pane_id", pane -> pane.id().value());
    private static final Fields.TextField<Pane> COMMAND = Fields.text("pane_current_command", Pane::currentCommand);
    private static final Fields.NumberField<Pane> INDEX = Fields.number("pane_index", Pane::index);
    private static final Fields.FlagField<Pane> ACTIVE = Fields.flag("pane_active", Pane::active);
    private static final Fields.TextField<Pane> TITLE = Fields.text("pane_title", Pane::title);
    private static final Fields.TextField<Pane> PATH = Fields.text("pane_current_path", Pane::currentPathText);
    private static final Fields.NumberField<Pane> WIDTH =
            Fields.number("pane_width", pane -> pane.size().width());
    private static final Fields.NumberField<Pane> HEIGHT =
            Fields.number("pane_height", pane -> pane.size().height());
    private static final Fields.NumberField<Pane> LEFT =
            Fields.number("pane_left", pane -> pane.position().left());
    private static final Fields.NumberField<Pane> TOP =
            Fields.number("pane_top", pane -> pane.position().top());

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

    /** The pane's title, which a program running in it can set. */
    public static Fields.TextField<Pane> title() {
        return TITLE;
    }

    /** The pane's working directory, as the text tmux reported. */
    public static Fields.TextField<Pane> path() {
        return PATH;
    }

    /** The pane's width, in cells. */
    public static Fields.NumberField<Pane> width() {
        return WIDTH;
    }

    /** The pane's height, in cells. */
    public static Fields.NumberField<Pane> height() {
        return HEIGHT;
    }

    /** The column of the pane's left edge in its window. */
    public static Fields.NumberField<Pane> left() {
        return LEFT;
    }

    /** The row of the pane's top edge in its window. */
    public static Fields.NumberField<Pane> top() {
        return TOP;
    }
}
