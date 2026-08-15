package com.git_pull.libtmux.query;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Base for a generated or hand-written entity metamodel.
 *
 * <p>Minting a {@linkplain FieldProvenance.Canonical canonical} field is what makes a field eligible
 * for backend pushdown, so it is deliberately not something a caller can assert. The factories are
 * {@code protected}: reaching them requires declaring the type a metamodel by extending this class,
 * which is a visible act rather than a boolean somebody can set.
 */
public abstract class EntityMetamodel {

    protected EntityMetamodel() {}

    protected static <T> Fields.TextField<T> text(String id, Function<T, String> accessor) {
        return new Fields.TextField<>(FieldRef.canonical(id, FieldKind.TEXT, accessor));
    }

    protected static <T> Fields.NumberField<T> number(String id, Function<T, Integer> accessor) {
        return new Fields.NumberField<>(FieldRef.canonical(id, FieldKind.NUMBER, accessor));
    }

    protected static <T> Fields.FlagField<T> flag(String id, Function<T, Boolean> accessor) {
        return new Fields.FlagField<>(FieldRef.canonical(id, FieldKind.FLAG, accessor));
    }

    protected static <T, R> Fields.ToManyRef<T, R> toMany(String id, Function<T, List<R>> navigate) {
        return Fields.toMany(id, navigate);
    }

    protected static <T, R> Fields.ToOneRef<T, R> toOne(String id, Function<T, Optional<R>> navigate) {
        return Fields.toOne(id, navigate);
    }
}
