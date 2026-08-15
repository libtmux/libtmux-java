package com.git_pull.libtmux.query;

import java.util.Objects;
import java.util.function.Function;

/**
 * A named, kinded accessor.
 *
 * <p>The name makes a built expression printable and serializable; the kind makes lowering
 * independent of the operand's runtime class; the provenance decides whether a backend compiler may
 * trust the name to describe what the accessor actually reads.
 */
public record FieldRef<T, V>(String id, FieldKind kind, Function<T, V> accessor, FieldProvenance provenance) {

    public FieldRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(provenance, "provenance");
    }

    /** A caller-supplied field. Never eligible for pushdown, because its accessor is opaque. */
    public static <T, V> FieldRef<T, V> derived(String id, FieldKind kind, Function<T, V> accessor) {
        return new FieldRef<>(id, kind, accessor, FieldProvenance.Derived.INSTANCE);
    }

    /** A field whose accessor is the library's own canonical read of {@code id}. */
    static <T, V> FieldRef<T, V> canonical(String id, FieldKind kind, Function<T, V> accessor) {
        return new FieldRef<>(id, kind, accessor, FieldProvenance.Canonical.INSTANCE);
    }

    /** Retained for diagnostics; the id is what a backend compiler may reference. */
    public String name() {
        return id;
    }
}
