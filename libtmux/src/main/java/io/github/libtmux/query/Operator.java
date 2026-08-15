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
