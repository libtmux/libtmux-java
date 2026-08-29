package io.github.libtmux.query;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Typed field handles.
 *
 * <p>A caller supplies each accessor, so no backend may trust the name without matching this exact
 * handle against a model it owns.
 *
 * <p>Each kind exposes only the operators its type supports, so {@code Pane_.index().startsWith(..)}
 * is a compile error rather than a runtime class cast. This is the half of the metamodel that has to
 * exist by hand or by generator; the generator only writes the accessors, never the semantics.
 */
public final class Fields {

    private Fields() {}

    public static <T> TextField<T> text(String id, Function<T, String> accessor) {
        return new TextField<>(new FieldRef<>(id, FieldKind.TEXT, accessor));
    }

    public static <T> NumberField<T> number(String id, Function<T, Integer> accessor) {
        return new NumberField<>(new FieldRef<>(id, FieldKind.NUMBER, accessor));
    }

    public static <T> FlagField<T> flag(String id, Function<T, Boolean> accessor) {
        return new FlagField<>(new FieldRef<>(id, FieldKind.FLAG, accessor));
    }

    public static <T, R> ToManyRef<T, R> toMany(String id, Function<T, List<R>> navigate) {
        return new ToManyRef<>(id, navigate);
    }

    public static <T, R> ToOneRef<T, R> toOne(String id, Function<T, Optional<R>> navigate) {
        return new ToOneRef<>(id, navigate);
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

        public FilterExpr<T> isNot(int value) {
            return new FilterExpr.Compare<>(ref, Operator.NOT_EQUALS, value);
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

        public FilterExpr<T> is(boolean value) {
            return new FilterExpr.Compare<>(ref, Operator.EQUALS, value);
        }

        public FilterExpr<T> isNot(boolean value) {
            return new FilterExpr.Compare<>(ref, Operator.NOT_EQUALS, value);
        }

        public FilterExpr<T> isTrue() {
            return is(true);
        }

        public FilterExpr<T> isFalse() {
            return is(false);
        }
    }

    /** To-many relation. Quantifiers are the only way in, so an unquantified relation cannot compile. */
    public static final class ToManyRef<T, R> {

        private final String id;
        private final Function<T, List<R>> navigate;

        private ToManyRef(String id, Function<T, List<R>> navigate) {
            this.id = FieldRef.requireId(id);
            this.navigate = Objects.requireNonNull(navigate, "navigate");
        }

        public String id() {
            return id;
        }

        public Function<T, List<R>> navigate() {
            return navigate;
        }

        public FilterExpr.ToMany<T, R> any(FilterExpr<R> predicate) {
            return new FilterExpr.ToMany<>(this, FilterExpr.Quantifier.ANY, predicate);
        }

        public FilterExpr.ToMany<T, R> all(FilterExpr<R> predicate) {
            return new FilterExpr.ToMany<>(this, FilterExpr.Quantifier.ALL, predicate);
        }

        public FilterExpr.ToMany<T, R> none(FilterExpr<R> predicate) {
            return new FilterExpr.ToMany<>(this, FilterExpr.Quantifier.NONE, predicate);
        }
    }

    /** To-one relation. */
    public static final class ToOneRef<T, R> {

        private final String id;
        private final Function<T, Optional<R>> navigate;

        private ToOneRef(String id, Function<T, Optional<R>> navigate) {
            this.id = FieldRef.requireId(id);
            this.navigate = Objects.requireNonNull(navigate, "navigate");
        }

        public String id() {
            return id;
        }

        public Function<T, Optional<R>> navigate() {
            return navigate;
        }

        public FilterExpr.ToOne<T, R> is(FilterExpr<R> predicate) {
            return new FilterExpr.ToOne<>(this, predicate);
        }
    }
}
