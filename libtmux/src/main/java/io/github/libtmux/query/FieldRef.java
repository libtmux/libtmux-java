package io.github.libtmux.query;

import java.util.Objects;
import java.util.function.Function;

/**
 * A named, kinded accessor.
 *
 * <p>The name makes a built expression printable; the kind makes consumers independent of the
 * operand's runtime class. A serializer or compiler must bind the exact handle against its own
 * model before trusting the name to describe what the accessor reads.
 */
public final class FieldRef<T, V> {

    private final String id;
    private final FieldKind kind;
    private final Function<T, V> accessor;

    FieldRef(String id, FieldKind kind, Function<T, V> accessor) {
        this.id = requireId(id);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.accessor = Objects.requireNonNull(accessor, "accessor");
    }

    static String requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return id;
    }

    public String id() {
        return id;
    }

    public FieldKind kind() {
        return kind;
    }

    public Function<T, V> accessor() {
        return accessor;
    }
}
