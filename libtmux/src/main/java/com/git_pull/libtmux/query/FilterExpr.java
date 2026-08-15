package com.git_pull.libtmux.query;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A filter that is both runnable and readable.
 *
 * <p>It extends {@link Predicate}, so {@code stream().filter(expr)} works with no adapter, and it is
 * a sealed tree of records, so the same value can be printed, serialized, or later compiled into a
 * tmux {@code -f} filter. A lambda can do the first of those and none of the rest, which is why
 * named expressions are built from these nodes instead.
 *
 * <p>Evaluation is a total function over the tree: every consumer switches exhaustively, so adding a
 * node breaks compilation at each site that has to learn about it rather than at runtime.
 */
public sealed interface FilterExpr<T> extends Predicate<T> {

    /** Renders the expression as inspectable text. Deliberately not {@code toString} on records. */
    String describe();

    // ---------------------------------------------------------------------------------------

    static <T> FilterExpr<T> and(List<FilterExpr<T>> operands) {
        return new And<>(operands);
    }

    static <T> FilterExpr<T> or(List<FilterExpr<T>> operands) {
        return new Or<>(operands);
    }

    default FilterExpr<T> and(FilterExpr<T> other) {
        return new And<>(List.of(this, other));
    }

    default FilterExpr<T> or(FilterExpr<T> other) {
        return new Or<>(List.of(this, other));
    }

    /**
     * Negation, narrowed to an expression.
     *
     * <p>{@code and} and {@code or} below are deliberately <em>overloads</em> of the inherited
     * {@link Predicate} methods rather than overrides: their parameter is a {@code FilterExpr}, so
     * combining two expressions stays an expression, while passing a bare lambda selects the
     * inherited method and yields a plain predicate. That is the intended boundary — a lambda cannot
     * be printed or serialized, so an expression built from one must not claim to be inspectable.
     */
    @Override
    default FilterExpr<T> negate() {
        return new Not<>(this);
    }

    // ---------------------------------------------------------------------------------------

    /** Conjunction. An empty conjunction is true, which is what makes it a safe identity. */
    record And<T>(List<FilterExpr<T>> operands) implements FilterExpr<T> {

        public And {
            operands = List.copyOf(operands);
        }

        @Override
        public boolean test(T value) {
            for (FilterExpr<T> operand : operands) {
                if (!operand.test(value)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String describe() {
            return operands.isEmpty()
                    ? "true"
                    : "("
                            + String.join(
                                    " and ",
                                    operands.stream().map(FilterExpr::describe).toList()) + ")";
        }
    }

    /** Disjunction. An empty disjunction is false. */
    record Or<T>(List<FilterExpr<T>> operands) implements FilterExpr<T> {

        public Or {
            operands = List.copyOf(operands);
        }

        @Override
        public boolean test(T value) {
            for (FilterExpr<T> operand : operands) {
                if (operand.test(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return operands.isEmpty()
                    ? "false"
                    : "("
                            + String.join(
                                    " or ",
                                    operands.stream().map(FilterExpr::describe).toList()) + ")";
        }
    }

    record Not<T>(FilterExpr<T> operand) implements FilterExpr<T> {

        @Override
        public boolean test(T value) {
            return !operand.test(value);
        }

        @Override
        public String describe() {
            return "not " + operand.describe();
        }
    }

    /** One scalar field compared to one operand. */
    record Compare<T, V>(FieldRef<T, V> field, Operator operator, Object operand) implements FilterExpr<T> {

        @Override
        public boolean test(T value) {
            return operator.matches(field.accessor().apply(value), operand);
        }

        @Override
        public String describe() {
            return field.name() + " " + operator.symbol() + " " + operand;
        }
    }

    /**
     * A quantified filter over a to-many relation.
     *
     * <p>{@code ALL} over an empty relation is true. That is the standard vacuous reading and it is
     * the one tmux users expect: a session with no windows does not fail "all windows are zoomed".
     */
    record ToMany<T, R>(String relation, Function<T, List<R>> navigate, Quantifier quantifier, FilterExpr<R> predicate)
            implements FilterExpr<T> {

        @Override
        public boolean test(T value) {
            List<R> related = navigate.apply(value);
            return switch (quantifier) {
                case ANY -> related.stream().anyMatch(predicate);
                case ALL -> related.stream().allMatch(predicate);
                case NONE -> related.stream().noneMatch(predicate);
            };
        }

        @Override
        public String describe() {
            return relation + " " + quantifier.name().toLowerCase(java.util.Locale.ROOT) + " (" + predicate.describe()
                    + ")";
        }
    }

    /** A to-one relation. An absent target does not satisfy the filter. */
    record ToOne<T, R>(String relation, Function<T, Optional<R>> navigate, FilterExpr<R> predicate)
            implements FilterExpr<T> {

        @Override
        public boolean test(T value) {
            return navigate.apply(value).filter(predicate).isPresent();
        }

        @Override
        public String describe() {
            return relation + " is (" + predicate.describe() + ")";
        }
    }

    enum Quantifier {
        ANY,
        ALL,
        NONE
    }
}
