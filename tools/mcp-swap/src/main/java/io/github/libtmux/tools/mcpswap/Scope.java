package io.github.libtmux.tools.mcpswap;

import java.util.Locale;

enum Scope {
    USER,
    PROJECT;

    static Scope parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("--scope must be user or project", error);
        }
    }

    String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
