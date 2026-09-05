package io.github.libtmux.mcp;

import java.util.Objects;

/** Text that may identify the tmux executable or socket but must never frame a command. */
final class RouteValue {

    private RouteValue() {}

    static String requireSafe(String value, String field) {
        Objects.requireNonNull(value, field);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || character == 0x7f) {
                throw new IllegalArgumentException(field + " contains an ASCII control character");
            }
        }
        return value;
    }
}
