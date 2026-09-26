package io.github.libtmux.query;

/**
 * The value kind a field reads.
 *
 * <p>Carried explicitly because exactness must not rest on the operand's runtime class. A number
 * field's operand happens to box to {@code Integer}, and reading the kind off that accident would
 * silently pick tmux's lexical comparison where the caller meant arithmetic.
 */
public enum FieldKind {
    TEXT,
    NUMBER,
    FLAG
}
