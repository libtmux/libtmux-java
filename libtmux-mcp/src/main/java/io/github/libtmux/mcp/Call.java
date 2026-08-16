package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One invocation: the tmux server to act on, what a model sent, and a way to say how it is going.
 *
 * <p>Arguments arrive as whatever JSON the client produced, so every reader here states what it
 * wanted when it does not get it. A model can correct "expected a number for 'timeout', got
 * \"thirty\"" and can do nothing with a class cast.
 */
record Call(Connection connection, Map<String, Object> arguments, Progress progress) {

    Server server() {
        return connection.server();
    }

    Caller caller() {
        return connection.caller();
    }

    Safety ceiling() {
        return connection.ceiling();
    }

    /** Reports how a slow tool is going, so a client can show it and a person can cancel it. */
    interface Progress {

        /** A no-op for callers outside the protocol, which is every test that exercises a tool. */
        Progress SILENT = (elapsed, total, message) -> {};

        void report(Duration elapsed, Duration total, String message);
    }

    String string(String name) {
        return maybe(name).orElseThrow(() -> new IllegalArgumentException("missing required argument '" + name + "'"));
    }

    Optional<String> maybe(String name) {
        Object value = arguments.get(name);
        if (value == null || (value instanceof String text && text.isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
    }

    int integer(String name, int fallback) {
        Object value = arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a whole number for '" + name + "', got " + value);
        }
    }

    double number(String name, double fallback) {
        Object value = arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number for '" + name + "', got " + value);
        }
    }

    boolean flag(String name, boolean fallback) {
        Object value = arguments.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean decided) {
            return decided;
        }
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException("expected true or false for '" + name + "', got " + value);
    }

    /**
     * A list of strings, tolerating the single string a model sends when it has only one.
     *
     * <p>Models do send {@code "error:"} where the schema says {@code ["error:"]}, and refusing it
     * spends a turn on a correction that changes nothing about what was meant.
     */
    List<String> strings(String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> many) {
            return many.stream()
                    .filter(item -> item != null)
                    .map(Object::toString)
                    .toList();
        }
        String single = value.toString();
        return single.isEmpty() ? List.of() : List.of(single);
    }
}
