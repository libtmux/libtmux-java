package io.github.libtmux.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A small native output contract shared by discovery and runtime validation. */
record OutputSchema(
        Map<String, ValueType> properties, Set<String> required, Map<String, Map<String, Object>> propertySchemas) {

    OutputSchema(Map<String, ValueType> properties) {
        this(properties, properties.keySet(), Map.of());
    }

    OutputSchema {
        Objects.requireNonNull(properties, "properties");
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("output schema has no properties");
        }
        Set<String> propertyNames = Set.copyOf(properties.keySet());
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
        if (!propertyNames.containsAll(required)) {
            throw new IllegalArgumentException("required output fields are absent from properties");
        }
        Map<String, Map<String, Object>> copiedSchemas = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : propertySchemas.entrySet()) {
            if (!propertyNames.contains(entry.getKey())) {
                throw new IllegalArgumentException("output schema override has no property '" + entry.getKey() + "'");
            }
            copiedSchemas.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        propertySchemas = Collections.unmodifiableMap(copiedSchemas);
    }

    enum ValueType {
        STRING("string"),
        INTEGER("integer"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        ARRAY("array"),
        OBJECT("object");

        private final String wireName;

        ValueType(String wireName) {
            this.wireName = wireName;
        }

        Map<String, Object> schema() {
            return Map.of("type", wireName);
        }

        boolean accepts(Object value) {
            return switch (this) {
                case STRING -> value instanceof String;
                case INTEGER ->
                    value instanceof Byte
                            || value instanceof Short
                            || value instanceof Integer
                            || value instanceof Long;
                case NUMBER -> value instanceof Number;
                case BOOLEAN -> value instanceof Boolean;
                case ARRAY ->
                    value instanceof Iterable<?>
                            || (value != null && value.getClass().isArray());
                case OBJECT -> value instanceof Map<?, ?>;
            };
        }
    }

    Map<String, Object> wireSchema() {
        Map<String, Object> fields = new LinkedHashMap<>();
        properties.forEach((name, type) -> fields.put(name, propertySchemas.getOrDefault(name, type.schema())));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(fields));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    void validate(String tool, Object answer) {
        validateAgainst(tool, "$", Answers.asObject(answer), wireSchema());
    }

    OutputSchema withPropertySchema(String name, Map<String, Object> schema) {
        if (!properties.containsKey(name)) {
            throw new IllegalArgumentException("output has no field '" + name + "'");
        }
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>(propertySchemas);
        schemas.put(name, schema);
        return new OutputSchema(properties, required, schemas);
    }

    OutputSchema withOptionalFields(String... names) {
        Set<String> nextRequired = new LinkedHashSet<>(required);
        for (String name : names) {
            if (!properties.containsKey(name)) {
                throw new IllegalArgumentException("output has no field '" + name + "'");
            }
            nextRequired.remove(name);
        }
        return new OutputSchema(properties, nextRequired, propertySchemas);
    }

    static OutputSchema of(Field first, Field... rest) {
        Map<String, ValueType> fields = new LinkedHashMap<>();
        add(fields, first);
        for (Field field : rest) {
            add(fields, field);
        }
        return new OutputSchema(fields);
    }

    static OutputSchema ofRecord(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record output type");
        }
        Map<String, ValueType> fields = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
            String name = toSnakeCase(component.getName());
            fields.put(name, valueType(component.getType()));
            schemas.put(name, schemaFor(component.getGenericType()));
            required.add(name);
        }
        return new OutputSchema(fields, required, schemas);
    }

    private static Map<String, Object> schemaFor(java.lang.reflect.Type type) {
        if (type instanceof java.lang.reflect.ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) {
            if (Iterable.class.isAssignableFrom(raw)) {
                return ordered("type", "array", "items", schemaFor(parameterized.getActualTypeArguments()[0]));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return ordered(
                        "type",
                        "object",
                        "additionalProperties",
                        schemaFor(parameterized.getActualTypeArguments()[1]));
            }
        }
        if (type instanceof Class<?> concrete) {
            if (concrete.isRecord()) {
                return ofRecord(concrete).wireSchema();
            }
            return valueType(concrete).schema();
        }
        return Map.of();
    }

    private static ValueType valueType(Class<?> type) {
        if (type == String.class || type == Character.class || type == char.class) {
            return ValueType.STRING;
        }
        if (type == byte.class
                || type == short.class
                || type == int.class
                || type == long.class
                || (Number.class.isAssignableFrom(type) && type != Float.class && type != Double.class)) {
            return ValueType.INTEGER;
        }
        if (type == float.class || type == double.class || type == Float.class || type == Double.class) {
            return ValueType.NUMBER;
        }
        if (type == boolean.class || type == Boolean.class) {
            return ValueType.BOOLEAN;
        }
        if (Iterable.class.isAssignableFrom(type) || type.isArray()) {
            return ValueType.ARRAY;
        }
        if (Map.class.isAssignableFrom(type) || type.isRecord()) {
            return ValueType.OBJECT;
        }
        throw new IllegalArgumentException("unsupported output type " + type.getName());
    }

    private static String toSnakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static void add(Map<String, ValueType> fields, Field field) {
        if (fields.put(field.name(), field.type()) != null) {
            throw new IllegalArgumentException("duplicate output field '" + field.name() + "'");
        }
    }

    private static boolean nullLike(@Nullable Object value) {
        return value == null || value instanceof com.fasterxml.jackson.databind.node.NullNode;
    }

    private static void validateAgainst(String tool, String path, @Nullable Object value, Map<String, Object> schema) {
        Object alternatives = schema.get("oneOf");
        if (alternatives instanceof Iterable<?> choices) {
            for (Object choice : choices) {
                if (choice instanceof Map<?, ?> option && matches(tool, path, value, option)) {
                    return;
                }
            }
            throw invalid(tool, path, "does not match any declared output alternative");
        }

        Object type = schema.get("type");
        if (nullLike(value)) {
            if ("null".equals(type)) {
                return;
            }
            throw invalid(tool, path, "is null");
        }
        if (!(type instanceof String expected)) {
            return;
        }
        Object present = Objects.requireNonNull(value);
        switch (expected) {
            case "string" -> require(tool, path, present instanceof String, expected);
            case "integer" -> require(tool, path, ValueType.INTEGER.accepts(present), expected);
            case "number" -> require(tool, path, present instanceof Number, expected);
            case "boolean" -> require(tool, path, present instanceof Boolean, expected);
            case "array" -> validateArray(tool, path, present, schema.get("items"));
            case "object" -> validateObject(tool, path, present, schema);
            case "null" -> throw invalid(tool, path, "is not null");
            default -> throw invalid(tool, path, "uses unsupported schema type " + expected);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean matches(String tool, String path, @Nullable Object value, Map<?, ?> schema) {
        try {
            validateAgainst(tool, path, value, (Map<String, Object>) schema);
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static void validateArray(String tool, String path, Object value, @Nullable Object itemSchema) {
        if (!(value instanceof Iterable<?> values)) {
            throw invalid(tool, path, "is not an array");
        }
        if (!(itemSchema instanceof Map<?, ?> schema)) {
            return;
        }
        int index = 0;
        for (Object item : values) {
            validateAgainst(tool, path + "[" + index + "]", item, castSchema(schema));
            index++;
        }
    }

    private static void validateObject(String tool, String path, Object value, Map<String, Object> schema) {
        Map<String, Object> object;
        if (value instanceof Map<?, ?>) {
            object = Answers.asObject(value);
        } else if (value.getClass().isRecord()) {
            object = Answers.asObject(value);
        } else {
            throw invalid(tool, path, "is not an object");
        }

        Map<String, Object> declared =
                schema.get("properties") instanceof Map<?, ?> fields ? castSchema(fields) : Map.of();
        if (schema.get("required") instanceof Iterable<?> requiredFields) {
            for (Object requiredField : requiredFields) {
                if (!(requiredField instanceof String name) || !object.containsKey(name)) {
                    throw invalid(tool, path, "omits required field " + requiredField);
                }
            }
        }
        Object additional = schema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Object fieldSchema = declared.get(entry.getKey());
            if (fieldSchema instanceof Map<?, ?> field) {
                validateAgainst(tool, path + "." + entry.getKey(), entry.getValue(), castSchema(field));
            } else if (additional instanceof Map<?, ?> values) {
                validateAgainst(tool, path + "." + entry.getKey(), entry.getValue(), castSchema(values));
            } else if (Boolean.FALSE.equals(additional)) {
                throw invalid(tool, path, "contains undeclared field " + entry.getKey());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSchema(Map<?, ?> schema) {
        return (Map<String, Object>) schema;
    }

    private static void require(String tool, String path, boolean accepted, String expected) {
        if (!accepted) {
            throw invalid(tool, path, "is not " + expected);
        }
    }

    private static IllegalStateException invalid(String tool, String path, String message) {
        return new IllegalStateException(tool + " output " + path + " " + message);
    }

    private static Map<String, Object> ordered(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("schema entries must be key-value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    record Field(String name, ValueType type) {
        Field {
            if (Objects.requireNonNull(name, "name").isBlank()) {
                throw new IllegalArgumentException("output field name is blank");
            }
            Objects.requireNonNull(type, "type");
        }
    }
}
