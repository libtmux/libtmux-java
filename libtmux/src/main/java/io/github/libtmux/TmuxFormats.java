package io.github.libtmux;

import java.util.Objects;

/** Values handed to a tmux argument position that expands formats. */
final class TmuxFormats {

    private TmuxFormats() {}

    /** Makes one caller value literal at one construction boundary. */
    static String literal(String value) {
        return Objects.requireNonNull(value, "value").replace("#", "##");
    }
}
