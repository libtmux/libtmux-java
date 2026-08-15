package io.github.libtmux;

/**
 * One of tmux's built-in pane arrangements.
 *
 * <p>An enum rather than a string, and not for tidiness: {@code select-layout} with a name tmux does
 * not recognise <em>ends the server</em> on 3.3a, taking every session on that socket with it —
 * including sessions this program never created. Every other supported release answers
 * {@code invalid layout} and carries on.
 *
 * <p>A name that cannot be misspelled cannot trigger that. {@link Window#applyLayout} covers the
 * other way in, a serialized layout string, by checking it before tmux sees it.
 */
public enum Layout {
    EVEN_HORIZONTAL("even-horizontal"),
    EVEN_VERTICAL("even-vertical"),
    MAIN_HORIZONTAL("main-horizontal"),
    MAIN_VERTICAL("main-vertical"),
    TILED("tiled"),

    /** Requires tmux 3.5. */
    MAIN_HORIZONTAL_MIRRORED("main-horizontal-mirrored"),

    /** Requires tmux 3.5. */
    MAIN_VERTICAL_MIRRORED("main-vertical-mirrored");

    private final String name;

    Layout(String name) {
        this.name = name;
    }

    /** The name tmux knows this by. */
    public String tmuxName() {
        return name;
    }

    /**
     * The first supported release that has this layout.
     *
     * <p>A switch rather than a field, so that adding a layout cannot compile until someone says
     * which release brought it — and so the enum holds nothing but its name.
     */
    TmuxVersion since() {
        return switch (this) {
            case MAIN_HORIZONTAL_MIRRORED, MAIN_VERTICAL_MIRRORED -> new TmuxVersion(3, 5, "");
            case EVEN_HORIZONTAL, EVEN_VERTICAL, MAIN_HORIZONTAL, MAIN_VERTICAL, TILED -> new TmuxVersion(3, 2, "a");
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
