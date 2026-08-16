package io.github.libtmux.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        @Nullable Object fallback) {

    static Argument required(String name, String description) {
        return new Argument(name, "string", description, true, null);
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

    static Argument seconds(String name, String description, double fallback) {
        return new Argument(name, "number", description, false, fallback);
    }

    static Argument flag(String name, String description, boolean fallback) {
        return new Argument(name, "boolean", description, false, fallback);
    }

    static Argument strings(String name, String description) {
        return new Argument(name, "array", description, false, null);
    }

    /** The JSON Schema fragment describing this one argument. */
    Map<String, Object> schema() {
        Map<String, Object> described = new LinkedHashMap<>();
        if ("array".equals(type)) {
            // A single value is accepted where a list is wanted, because models send one — and the
            // schema has to say so. The server validates arguments against this before a tool sees
            // them, so a reader that quietly coped with a bare string would never be reached.
            described.put("type", List.of("array", "string"));
            described.put("items", Map.of("type", "string"));
        } else {
            described.put("type", type);
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
        return schema;
    }
}
