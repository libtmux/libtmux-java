package io.github.libtmux;

/**
 * A mode a pane can be in, as tmux names them.
 *
 * <p>A pane in a mode shows something other than its program: its own scrollback, a clock, or one of
 * the choosers. Every mode below exists on each supported release.
 */
public enum PaneMode {

    /** Scrollback, navigable and selectable. Entered by {@link Pane#copyMode()}. */
    COPY("copy-mode"),

    /**
     * Scrollback without the selection commands.
     *
     * <p>tmux enters this itself, for the output of a command it was asked to display; no command
     * here puts a pane into it.
     */
    VIEW("view-mode"),

    /** A clock. Entered by {@link Pane#clockMode()}. */
    CLOCK("clock-mode"),

    /** The session and window browser, which the window finder also opens. */
    TREE("tree-mode"),

    /** The attached-client browser. */
    CLIENT("client-mode"),

    /** The paste-buffer browser. */
    BUFFER("buffer-mode"),

    /** The option browser. Entered by {@link Pane#customizeMode()}. */
    OPTIONS("options-mode");

    private final String reported;

    PaneMode(String reported) {
        this.reported = reported;
    }

    /** What tmux calls it, which is what {@code #{pane_mode}} expands to. */
    public String reported() {
        return reported;
    }

    /**
     * Reads what tmux reported.
     *
     * @throws LibTmuxException if this tmux named a mode this release range does not have
     */
    static PaneMode of(String reported) {
        for (PaneMode mode : values()) {
            if (mode.reported.equals(reported)) {
                return mode;
            }
        }
        throw new LibTmuxException("tmux reported a mode this library does not know: " + reported);
    }
}
