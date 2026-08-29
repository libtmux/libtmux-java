package io.github.libtmux.query;

import java.util.Collection;
import java.util.Objects;
import java.util.regex.Pattern;

/** The scalar comparisons an expression can hold. Evaluation stays a total switch. */
public enum Operator {
    EQUALS("=="),
    NOT_EQUALS("!="),
    CONTAINS("contains"),
    STARTS_WITH("starts-with"),
    ENDS_WITH("ends-with"),
    MATCHES("matches"),
    LESS_THAN("<"),
    AT_MOST("<="),
    GREATER_THAN(">"),
    AT_LEAST(">="),
    IN("in");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    void requireOperand(FieldKind kind, Object operand) {
        Objects.requireNonNull(kind, "kind");
        if (!supports(kind)) {
            throw new IllegalArgumentException(name() + " does not support " + kind + " fields");
        }
        boolean valid =
                switch (this) {
                    case MATCHES -> operand instanceof Pattern;
                    case IN ->
                        operand instanceof Collection<?> values
                                && values.stream().allMatch(value -> scalar(kind, value));
                    default -> scalar(kind, operand);
                };
        if (!valid) {
            throw new IllegalArgumentException(name() + " has an invalid operand for a " + kind + " field");
        }
    }

    private boolean supports(FieldKind kind) {
        return switch (this) {
            case EQUALS, NOT_EQUALS -> true;
            case CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, IN -> kind == FieldKind.TEXT;
            case LESS_THAN, AT_MOST, GREATER_THAN, AT_LEAST -> kind == FieldKind.NUMBER;
        };
    }

    private static boolean scalar(FieldKind kind, Object operand) {
        return switch (kind) {
            case TEXT -> operand instanceof String;
            case NUMBER -> operand instanceof Integer;
            case FLAG -> operand instanceof Boolean;
        };
    }

    @SuppressWarnings("unchecked")
    boolean matches(Object actual, Object operand) {
        return switch (this) {
            case EQUALS -> Objects.equals(actual, operand);
            case NOT_EQUALS -> !Objects.equals(actual, operand);
            case CONTAINS -> text(actual).contains((String) operand);
            case STARTS_WITH -> text(actual).startsWith((String) operand);
            case ENDS_WITH -> text(actual).endsWith((String) operand);
            case MATCHES -> ((Pattern) operand).matcher(text(actual)).find();
            case IN -> ((Collection<?>) operand).contains(actual);
            case LESS_THAN -> compare(actual, operand) < 0;
            case AT_MOST -> compare(actual, operand) <= 0;
            case GREATER_THAN -> compare(actual, operand) > 0;
            case AT_LEAST -> compare(actual, operand) >= 0;
        };
    }

    private static String text(Object actual) {
        return actual == null ? "" : actual.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object actual, Object operand) {
        return ((Comparable) actual).compareTo(operand);
    }
}
