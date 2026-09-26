package io.github.libtmux.junit5;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The part of tmux's format language the library sends: variables, comparisons, {@code &&} and
 * {@code ||}, {@code ?} conditionals, and the {@code W:} and {@code P:} loops.
 */
final class Formats {

    private Formats() {}

    /** What a format is expanded against: a row's variables, and the rows a loop visits. */
    interface Scope {
        @Nullable
        String get(String variable);

        /** The windows of this row's session, each at its active pane. */
        List<Scope> windows();

        /** The panes of this row's window. */
        List<Scope> panes();
    }

    /** Undoes {@code TmuxFormats.literal}: tmux reads {@code ##} in a format as one {@code #}. */
    static String literal(String value) {
        return value.replace("##", "#");
    }

    static String expand(String format, Function<String, @Nullable String> variables) {
        return expand(format, new Scope() {
            @Override
            public @Nullable String get(String variable) {
                return variables.apply(variable);
            }

            @Override
            public List<Scope> windows() {
                return List.of();
            }

            @Override
            public List<Scope> panes() {
                return List.of();
            }
        });
    }

    static String expand(String format, Scope variables) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < format.length()) {
            if (format.startsWith("##", index)) {
                out.append('#');
                index += 2;
            } else if (format.startsWith("#{", index)) {
                int end = closing(format, index + 2);
                out.append(evaluate(format.substring(index + 2, end), variables));
                index = end + 1;
            } else {
                out.append(format.charAt(index));
                index++;
            }
        }
        return out.toString();
    }

    private static String evaluate(String inner, Scope variables) {
        if (inner.startsWith("?")) {
            List<String> branches = split(inner.substring(1));
            String condition = branches.get(0);
            boolean holds = truthy(
                    condition.startsWith("#{")
                            ? expand(condition, variables)
                            : Objects.requireNonNullElse(variables.get(condition), ""));
            int chosen = holds ? 1 : 2;
            return chosen < branches.size() ? expand(branches.get(chosen), variables) : "";
        }
        if (inner.startsWith("W:") || inner.startsWith("P:")) {
            String body = inner.substring(2);
            StringBuilder looped = new StringBuilder();
            for (Scope each : inner.charAt(0) == 'W' ? variables.windows() : variables.panes()) {
                looped.append(expand(body, each));
            }
            return looped.toString();
        }
        if (inner.startsWith("!:")) {
            return truthy(expand(inner.substring(2), variables)) ? "0" : "1";
        }
        for (String operator : List.of("e|<=|:", "e|>=|:", "e|<|:", "e|>|:")) {
            if (inner.startsWith(operator)) {
                List<String> operands = split(inner.substring(operator.length())).stream()
                        .map(operand -> expand(operand, variables))
                        .toList();
                int order = Long.compare(number(operands, 0), number(operands, 1));
                boolean result =
                        switch (operator) {
                            case "e|<=|:" -> order <= 0;
                            case "e|>=|:" -> order >= 0;
                            case "e|<|:" -> order < 0;
                            default -> order > 0;
                        };
                return result ? "1" : "0";
            }
        }
        for (String operator : List.of("==:", "!=:", "&&:", "||:")) {
            if (inner.startsWith(operator)) {
                List<String> operands = split(inner.substring(operator.length())).stream()
                        .map(operand -> expand(operand, variables))
                        .toList();
                String left = operands.isEmpty() ? "" : operands.get(0);
                String right = operands.size() < 2 ? "" : operands.get(1);
                boolean result =
                        switch (operator) {
                            case "==:" -> left.equals(right);
                            case "!=:" -> !left.equals(right);
                            case "&&:" -> truthy(left) && truthy(right);
                            default -> truthy(left) || truthy(right);
                        };
                return result ? "1" : "0";
            }
        }
        String value = variables.get(inner);
        return value == null ? "" : value;
    }

    /** tmux reads a missing or non-numeric operand as zero. */
    private static long number(List<String> operands, int index) {
        try {
            return index < operands.size() ? Long.parseLong(operands.get(index).strip()) : 0;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static int closing(String format, int from) {
        int depth = 1;
        for (int index = from; index < format.length(); index++) {
            if (format.startsWith("#{", index)) {
                depth++;
                index++;
            } else if (format.charAt(index) == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return format.length() - 1;
    }

    /** Splits operands at commas that are not inside a nested format. */
    private static List<String> split(String operands) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < operands.length(); index++) {
            if (operands.startsWith("#{", index)) {
                depth++;
                index++;
            } else if (operands.charAt(index) == '}') {
                depth--;
            } else if (operands.charAt(index) == ',' && depth == 0) {
                parts.add(operands.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(operands.substring(start));
        return Collections.unmodifiableList(parts);
    }

    static boolean truthy(String value) {
        return !value.isEmpty() && !value.equals("0");
    }
}
