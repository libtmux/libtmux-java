package io.github.libtmux;

import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.support.Capabilities;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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

    private static final Map<String, Layout> BY_TMUX_NAME =
            Arrays.stream(values()).collect(java.util.stream.Collectors.toMap(Layout::tmuxName, Function.identity()));

    /**
     * The layout with this exact tmux name, or empty if none matches.
     *
     * <p>One map built once, so a caller that already has a canonical name does not repeat the scan
     * over {@link #values()} that resolving an abbreviation already did.
     */
    public static Optional<Layout> byTmuxName(String name) {
        return Optional.ofNullable(BY_TMUX_NAME.get(name));
    }

    /**
     * The first supported release that has this layout, or empty when every supported release does.
     *
     * <p>A switch rather than a field, so that adding a layout cannot compile until someone says
     * which release brought it — and so the enum holds nothing but its name.
     */
    Optional<TmuxVersion> since() {
        return switch (this) {
            case MAIN_HORIZONTAL_MIRRORED, MAIN_VERTICAL_MIRRORED ->
                Optional.of(Capabilities.LAYOUT_MIRRORED_MAIN.since());
            case EVEN_HORIZONTAL, EVEN_VERTICAL, MAIN_HORIZONTAL, MAIN_VERTICAL, TILED -> Optional.empty();
        };
    }

    /** Whether the running tmux has this layout. */
    boolean availableOn(TmuxVersion running) {
        return since().map(running::atLeast).orElse(true);
    }

    /** Refuses this layout when the running tmux predates it. */
    public void requireSupported(TmuxVersion running) {
        if (!availableOn(running)) {
            throw new UnsupportedFeatureException("the " + this + " layout", since().orElseThrow(), running);
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
