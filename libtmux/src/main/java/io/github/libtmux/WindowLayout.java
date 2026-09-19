package io.github.libtmux;

import java.util.Objects;

/**
 * A window's layout as tmux reported it, in whichever of its two forms it used.
 *
 * <p>tmux writes a layout in its classic checksummed form — {@code c3a4,80x24,0,0,1} — on every
 * release before 3.8, and as JSON from 3.8, which also names the pane in each cell. The same window
 * therefore reports a differently shaped string depending on the server, and a caller that parses it
 * needs to know which it has. This says, so the compiler can carry what used to be a comment:
 *
 * <pre>{@code
 * switch (window.layout()) {
 *     case WindowLayout.Json json -> restorePanes(json.value());
 *     case WindowLayout.Classic classic -> restoreGeometry(classic.value());
 * }
 * }</pre>
 *
 * <p>Either form is an opaque token to hand back to tmux, which {@link Window#applyLayout(WindowLayout)}
 * does; parsing one is tmux's job, and {@link #value()} is the text for a caller who wants to anyway.
 */
public sealed interface WindowLayout {

    /** The layout exactly as tmux reported it. */
    String value();

    /** The checksummed form every release writes before 3.8. It carries geometry and no pane ids. */
    record Classic(String value) implements WindowLayout {

        public Classic {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The JSON form tmux writes from 3.8, which names the pane in each cell. */
    record Json(String value) implements WindowLayout {

        public Json {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Which form a reported layout is in.
     *
     * <p>Decided as tmux decides it, on whether the first non-blank character opens a JSON object —
     * {@code layout_construct} in {@code layout-custom.c} dispatches on nothing more.
     */
    static WindowLayout of(String reported) {
        Objects.requireNonNull(reported, "reported");
        return reported.strip().startsWith("{") ? new Json(reported) : new Classic(reported);
    }
}
