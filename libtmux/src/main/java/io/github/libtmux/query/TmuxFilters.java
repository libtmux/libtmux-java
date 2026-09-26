package io.github.libtmux.query;

import java.util.Optional;

/**
 * Lowers a {@link FilterExpr} to a tmux {@code -f} format when every node is one tmux can apply.
 *
 * <p>Relations, a Java regular expression, and any operand containing {@code ,}, <code>#</code>,
 * <code>{</code>, <code>}</code>, {@code :}, or a backslash, are refused. Those stay a local filter over a capture. tmux
 * compiles {@code #{m/r:}} with its own dialect, so a pattern Java would match can select nothing.
 * A compiled format is a hint to tmux, not a promise that the local reading would differ: callers
 * still apply the expression to the rows that come back.
 */
public final class TmuxFilters {

    private TmuxFilters() {}

    /**
     * A tmux format for {@code -f}, or empty when this expression is not a safe subset.
     */
    public static Optional<String> format(FilterExpr<?> expression) {
        try {
            return Optional.of(compile(expression));
        } catch (UnsupportedFilter unsupported) {
            return Optional.empty();
        }
    }

    /**
     * Whether this text can sit inside a tmux format argument without changing its shape. A
     * backslash escapes the next character in a format, so a trailing one swallows the closing brace.
     */
    public static boolean literal(String value) {
        return value.indexOf(',') < 0
                && value.indexOf('\\') < 0
                && value.indexOf('#') < 0
                && value.indexOf('{') < 0
                && value.indexOf('}') < 0
                && value.indexOf(':') < 0;
    }

    private static String compile(FilterExpr<?> expression) {
        return switch (expression) {
            case FilterExpr.And<?> and ->
                join("&&", and.operands().stream().map(TmuxFilters::compile).toList(), "1");
            case FilterExpr.Or<?> or ->
                join("||", or.operands().stream().map(TmuxFilters::compile).toList(), "0");
            case FilterExpr.Not<?> not -> "#{!:" + compile(not.operand()) + "}";
            case FilterExpr.Compare<?, ?> compare -> compare(compare);
            case FilterExpr.ToMany<?, ?> ignored -> refuse();
            case FilterExpr.ToOne<?, ?> ignored -> refuse();
        };
    }

    private static String join(String operator, java.util.List<String> parts, String identity) {
        if (parts.isEmpty()) {
            return identity;
        }
        String combined = parts.get(parts.size() - 1);
        for (int index = parts.size() - 2; index >= 0; index--) {
            combined = "#{" + operator + ":" + parts.get(index) + "," + combined + "}";
        }
        return combined;
    }

    private static String compare(FilterExpr.Compare<?, ?> compare) {
        String field = "#{" + compare.field().id() + "}";
        return switch (compare.operator()) {
            case EQUALS -> "#{==:" + field + "," + scalar(compare.operand()) + "}";
            case NOT_EQUALS -> "#{!=:" + field + "," + scalar(compare.operand()) + "}";
            case CONTAINS -> "#{m:*" + glob(text(compare.operand())) + "*," + field + "}";
            case STARTS_WITH -> "#{m:" + glob(text(compare.operand())) + "*," + field + "}";
            case ENDS_WITH -> "#{m:*" + glob(text(compare.operand())) + "," + field + "}";
            case MATCHES -> refuse();
            // #{<:} compares text, so 9 would sort after 10; #{e|<|:} compares numbers.
            case LESS_THAN -> "#{e|<|:" + field + "," + compare.operand() + "}";
            case AT_MOST -> "#{e|<=|:" + field + "," + compare.operand() + "}";
            case GREATER_THAN -> "#{e|>|:" + field + "," + compare.operand() + "}";
            case AT_LEAST -> "#{e|>=|:" + field + "," + compare.operand() + "}";
            case IN -> in(field, compare.operand());
        };
    }

    private static String scalar(Object operand) {
        if (operand instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        String text = String.valueOf(operand);
        if (!literal(text)) {
            refuse();
        }
        return text;
    }

    private static String text(Object operand) {
        String value = (String) operand;
        if (!literal(value)) {
            refuse();
        }
        return value;
    }

    /** fnmatch metacharacters in the caller's text stay literal. */
    private static String glob(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '*' || character == '?' || character == '[' || character == '\\') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    @SuppressWarnings("unchecked")
    private static String in(String field, Object operand) {
        java.util.Collection<?> values = (java.util.Collection<?>) operand;
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Object value : values) {
            parts.add("#{==:" + field + "," + scalar(value) + "}");
        }
        return join("||", parts, "0");
    }

    private static String refuse() {
        throw new UnsupportedFilter();
    }

    private static final class UnsupportedFilter extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
