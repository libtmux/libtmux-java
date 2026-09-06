package io.github.libtmux.mcp;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One argument a tool takes, and everything a model needs to supply it.
 *
 * @param name the wire name
 * @param type the JSON Schema type
 * @param description what a model is told, which is the only documentation it gets
 * @param required whether omitting it is an error
 * @param fallback what the tool uses when it is omitted, or null when there is nothing to say
 */
record Argument(
        String name,
        String type,
        String description,
        boolean required,
        @Nullable Object fallback,
        @Nullable Integer maxLength,
        @Nullable Integer maxItems) {

    Argument(String name, String type, String description, boolean required, @Nullable Object fallback) {
        this(name, type, description, required, fallback, null, null);
    }

    static Argument required(String name, String description) {
        return new Argument(name, "string", description, true, null);
    }

    static Argument boundedRequired(String name, String description, int maxLength) {
        return new Argument(name, "string", description, true, null, maxLength, null);
    }

    static Argument optional(String name, String description) {
        return new Argument(name, "string", description, false, null);
    }

    static Argument paneId() {
        return new Argument(
                "pane_id", "string", "The pane to act on, such as %1. Every listing tool returns these.", true, null);
    }

    static Argument number(String name, String description, int fallback) {
        return new Argument(name, "integer", description, false, fallback);
    }

    static Argument requiredNumber(String name, String description) {
        return new Argument(name, "integer", description, true, null);
    }

    static Argument seconds(String name, String description, double fallback) {
        return new Argument(name, "number", description, false, fallback);
    }

    static Argument flag(String name, String description, boolean fallback) {
        return new Argument(name, "boolean", description, false, fallback);
    }

    static Argument strings(String name, String description) {
        return new Argument(name, "array", description, false, null);
    }

    static Argument boundedStrings(String name, String description, int maxLength, int maxItems) {
        return new Argument(name, "array", description, false, null, maxLength, maxItems);
    }

    static Argument objects(String name, String description) {
        return new Argument(name, "object-array", description, true, null);
    }

    static Argument boundedObjects(String name, String description, int maxItems) {
        return new Argument(name, "object-array", description, true, null, null, maxItems);
    }

    /** The JSON Schema fragment describing this one argument. */
    Map<String, Object> schema() {
        Map<String, Object> described = new LinkedHashMap<>();
        if ("array".equals(type) || "object-array".equals(type)) {
            // A single value is accepted where a list is wanted, because models send one — and the
            // schema has to say so. The server validates arguments against this before a tool sees
            // them, so a reader that quietly coped with a bare string would never be reached.
            described.put("type", "array".equals(type) ? List.of("array", "string") : "array");
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "array".equals(type) ? "string" : "object");
            if (maxLength != null) {
                items.put("maxLength", maxLength);
            }
            described.put("items", items);
            if (maxItems != null) {
                described.put("maxItems", maxItems);
            }
        } else {
            described.put("type", type);
            if (maxLength != null) {
                described.put("maxLength", maxLength);
            }
        }
        described.put(
                "description",
                description
                        + ("array".equals(type) ? " One value may be sent on its own, without a list." : "")
                        + (fallback == null ? "" : " Defaults to " + fallback + "."));
        if (fallback != null) {
            described.put("default", fallback);
        }
        return described;
    }

    /** The JSON Schema for a whole argument list, which is what a tool advertises. */
    static Map<String, Object> objectSchema(List<Argument> arguments) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = arguments.stream()
                .filter(Argument::required)
                .map(Argument::name)
                .toList();
        for (Argument argument : arguments) {
            properties.put(argument.name(), argument.schema());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Applies the same closed, typed input contract to nested and direct dispatch. */
    static void validate(List<Argument> declared, Map<String, Object> values) {
        Set<String> known = declared.stream().map(Argument::name).collect(java.util.stream.Collectors.toSet());
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown argument(s) " + unknown);
        }
        for (Argument argument : declared) {
            Object value = values.get(argument.name());
            if (value == null) {
                if (argument.required()) {
                    throw new IllegalArgumentException("missing required argument '" + argument.name() + "'");
                }
                continue;
            }
            argument.validate(value);
        }
    }

    private void validate(Object value) {
        boolean valid =
                switch (type) {
                    case "string" -> value instanceof String;
                    case "boolean" -> value instanceof Boolean;
                    case "number" -> value instanceof Number;
                    case "integer" ->
                        value instanceof Byte
                                || value instanceof Short
                                || value instanceof Integer
                                || value instanceof Long
                                || value instanceof java.math.BigInteger
                                || (value instanceof java.math.BigDecimal decimal
                                        && decimal.stripTrailingZeros().scale() <= 0)
                                || (value instanceof Float number
                                        && Float.isFinite(number)
                                        && number == Math.rint(number))
                                || (value instanceof Double number
                                        && Double.isFinite(number)
                                        && number == Math.rint(number));
                    case "array" ->
                        value instanceof String
                                || (value instanceof List<?> list
                                        && list.stream().allMatch(String.class::isInstance));
                    case "object-array" ->
                        value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance);
                    default -> false;
                };
        if (!valid) {
            throw new IllegalArgumentException("argument '" + name + "' must match schema type " + type);
        }
        if (maxLength != null) {
            if (value instanceof String text && text.length() > maxLength) {
                throw new IllegalArgumentException("argument '" + name + "' exceeds maxLength " + maxLength);
            }
            if (value instanceof List<?> list
                    && list.stream().map(String.class::cast).anyMatch(text -> text.length() > maxLength)) {
                throw new IllegalArgumentException("argument '" + name + "' contains an overlong value");
            }
        }
        if (maxItems != null && value instanceof List<?> list && list.size() > maxItems) {
            throw new IllegalArgumentException("argument '" + name + "' exceeds maxItems " + maxItems);
        }
    }
}
