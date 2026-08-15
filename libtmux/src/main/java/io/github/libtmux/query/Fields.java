package io.github.libtmux.query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Typed field handles.
 *
 * <p>Fields built here are {@linkplain FieldProvenance.Derived derived}: the caller supplied the
 * accessor, so no backend may assume the name describes what it reads. Canonical fields are minted by
 * {@link EntityMetamodel}.
 *
 * <p>Each kind exposes only the operators its type supports, so {@code Pane_.index().startsWith(..)}
 * is a compile error rather than a runtime class cast. This is the half of the metamodel that has to
 * exist by hand or by generator; the generator only writes the accessors, never the semantics.
 */
public final class Fields {

    private Fields() {}

    public static <T> TextField<T> text(String name, Function<T, String> accessor) {
        return new TextField<>(FieldRef.derived(name, FieldKind.TEXT, accessor));
    }

    public static <T> NumberField<T> number(String name, Function<T, Integer> accessor) {
        return new NumberField<>(FieldRef.derived(name, FieldKind.NUMBER, accessor));
    }

    public static <T> FlagField<T> flag(String name, Function<T, Boolean> accessor) {
        return new FlagField<>(FieldRef.derived(name, FieldKind.FLAG, accessor));
    }

    public static <T, R> ToManyRef<T, R> toMany(String name, Function<T, List<R>> navigate) {
        return new ToManyRef<>(name, navigate);
    }

    public static <T, R> ToOneRef<T, R> toOne(String name, Function<T, Optional<R>> navigate) {
        return new ToOneRef<>(name, navigate);
    }

    /** String-valued field. */
    public record TextField<T>(FieldRef<T, String> ref) {

        public FilterExpr<T> is(String value) {
            return new FilterExpr.Compare<>(ref, Operator.EQUALS, value);
        }

        public FilterExpr<T> isNot(String value) {
            return new FilterExpr.Compare<>(ref, Operator.NOT_EQUALS, value);
        }

        public FilterExpr<T> contains(String value) {
            return new FilterExpr.Compare<>(ref, Operator.CONTAINS, value);
        }

        public FilterExpr<T> startsWith(String value) {
            return new FilterExpr.Compare<>(ref, Operator.STARTS_WITH, value);
        }

        public FilterExpr<T> endsWith(String value) {
            return new FilterExpr.Compare<>(ref, Operator.ENDS_WITH, value);
        }

        public FilterExpr<T> matches(Pattern value) {
            return new FilterExpr.Compare<>(ref, Operator.MATCHES, value);
        }

        public FilterExpr<T> in(Collection<String> values) {
            return new FilterExpr.Compare<>(ref, Operator.IN, List.copyOf(values));
        }
    }

    /** Integer-valued field. No text operators reach it. */
    public record NumberField<T>(FieldRef<T, Integer> ref) {

        public FilterExpr<T> is(int value) {
            return new FilterExpr.Compare<>(ref, Operator.EQUALS, value);
        }

        public FilterExpr<T> lessThan(int value) {
            return new FilterExpr.Compare<>(ref, Operator.LESS_THAN, value);
        }

        public FilterExpr<T> atMost(int value) {
            return new FilterExpr.Compare<>(ref, Operator.AT_MOST, value);
        }

        public FilterExpr<T> greaterThan(int value) {
            return new FilterExpr.Compare<>(ref, Operator.GREATER_THAN, value);
        }

        public FilterExpr<T> atLeast(int value) {
            return new FilterExpr.Compare<>(ref, Operator.AT_LEAST, value);
        }
    }

    /** Boolean-valued field. */
    public record FlagField<T>(FieldRef<T, Boolean> ref) {

        public FilterExpr<T> isTrue() {
            return new FilterExpr.Compare<>(ref, Operator.EQUALS, true);
        }

        public FilterExpr<T> isFalse() {
            return new FilterExpr.Compare<>(ref, Operator.EQUALS, false);
        }
    }

    /** To-many relation. Quantifiers are the only way in, so an unquantified relation cannot compile. */
    public record ToManyRef<T, R>(String name, Function<T, List<R>> navigate) {

        public FilterExpr<T> any(FilterExpr<R> predicate) {
            return new FilterExpr.ToMany<>(name, navigate, FilterExpr.Quantifier.ANY, predicate);
        }

        public FilterExpr<T> all(FilterExpr<R> predicate) {
            return new FilterExpr.ToMany<>(name, navigate, FilterExpr.Quantifier.ALL, predicate);
        }

        public FilterExpr<T> none(FilterExpr<R> predicate) {
            return new FilterExpr.ToMany<>(name, navigate, FilterExpr.Quantifier.NONE, predicate);
        }
    }

    /** To-one relation. */
    public record ToOneRef<T, R>(String name, Function<T, Optional<R>> navigate) {

        public FilterExpr<T> is(FilterExpr<R> predicate) {
            return new FilterExpr.ToOne<>(name, navigate, predicate);
        }
    }
}
