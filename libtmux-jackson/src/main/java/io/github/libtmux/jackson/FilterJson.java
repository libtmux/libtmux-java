package io.github.libtmux.jackson;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.query.FieldKind;
import io.github.libtmux.query.FieldRef;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The versioned wire form of a filter expression.
 *
 * <p>Writing and reading both require a model. Expressions hold executable accessors and navigators,
 * while documents carry only their ids; the model is the authority that binds one to the other.
 * Regex operands retain {@link java.util.regex.Pattern} syntax and flag bits.
 *
 * <p>Everything unrecognised fails. An expression read wrongly does not announce itself — it
 * silently matches the wrong things, and a caller who wanted a filter gets one, just not theirs.
 */
public final class FilterJson {

    /** The schema this version reads and writes. Immutable once published. */
    public static final String SCHEMA = "libtmux.filter/1";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private FilterJson() {}

    /**
     * Writes an expression as a document for the model that declared all of its fields and relations.
     *
     * @throws SchemaException if any field or relation is not the exact handle declared by the model
     */
    public static <T> ObjectNode write(FilterExpr<T> expression, FilterModel<T> model) {
        ObjectNode document = JSON.createObjectNode();
        document.put("schema", SCHEMA);
        document.put("model", model.id());
        document.set("expr", node(expression, model));
        return document;
    }

    /** Writes an expression as compact JSON text. */
    public static <T> String writeString(FilterExpr<T> expression, FilterModel<T> model) {
        return write(expression, model).toString();
    }

    /**
     * Reads a document back as an expression over the given model.
     *
     * @throws SchemaException if the schema version, the model, a field, a relation, an operator or
     *     a node shape is not one this version knows
     */
    public static <T> FilterExpr<T> read(JsonNode document, FilterModel<T> model) {
        require(document.isObject(), "the document is not an object");
        requireOnly(document, "document", "schema", "model", "expr");
        String schema = text(document, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new SchemaException("unknown filter schema '" + schema + "', this reads " + SCHEMA);
        }
        String claimed = text(document, "model");
        if (!model.id().equals(claimed)) {
            throw new SchemaException("document is for model '" + claimed + "', not '" + model.id() + "'");
        }
        return expression(document.get("expr"), model);
    }

    /** Reads a document from JSON text. */
    public static <T> FilterExpr<T> readString(String json, FilterModel<T> model) {
        try {
            JsonNode document = JSON.readTree(json);
            if (document == null) {
                throw new SchemaException("the document is empty");
            }
            return read(document, model);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new SchemaException("the document is not readable JSON: " + e.getOriginalMessage());
        }
    }

    // ------------------------------------------------------------------------------------ write

    private static ObjectNode node(FilterExpr<?> expression, FilterModel<?> model) {
        ObjectNode node = JSON.createObjectNode();
        switch (expression) {
            case FilterExpr.And<?> and -> {
                node.put("node", "and");
                node.set("operands", operands(and.operands(), model));
            }
            case FilterExpr.Or<?> or -> {
                node.put("node", "or");
                node.set("operands", operands(or.operands(), model));
            }
            case FilterExpr.Not<?> not -> {
                node.put("node", "not");
                node.set("operand", node(not.operand(), model));
            }
            case FilterExpr.Compare<?, ?> compare -> {
                FieldRef<?, ?> field = compare.field();
                requireSame(field, model.field(field.id()), "field", field.id(), model);
                node.put("node", "compare");
                node.put("field", field.id());
                node.put("op", wire(compare.operator()));
                node.set("value", operand(compare.operand()));
            }
            case FilterExpr.ToMany<?, ?> toMany -> {
                var handle = toMany.relation();
                FilterModel.Relation<?, ?> relation = model.toMany(handle.id());
                requireSame(handle, relation.toMany(), "to-many relation", handle.id(), model);
                node.put("node", "to_many");
                node.put("relation", handle.id());
                node.put("quantifier", toMany.quantifier().name().toLowerCase(Locale.ROOT));
                node.set("predicate", node(toMany.predicate(), relation.target()));
            }
            case FilterExpr.ToOne<?, ?> toOne -> {
                var handle = toOne.relation();
                FilterModel.Relation<?, ?> relation = model.toOne(handle.id());
                requireSame(handle, relation.toOne(), "to-one relation", handle.id(), model);
                node.put("node", "to_one");
                node.put("relation", handle.id());
                node.set("predicate", node(toOne.predicate(), relation.target()));
            }
        }
        return node;
    }

    private static ArrayNode operands(List<? extends FilterExpr<?>> expressions, FilterModel<?> model) {
        ArrayNode array = JSON.createArrayNode();
        expressions.forEach(operand -> array.add(node(operand, model)));
        return array;
    }

    @SuppressWarnings("ReferenceEquality")
    private static void requireSame(
            Object actual,
            @org.jspecify.annotations.Nullable Object declared,
            String kind,
            String id,
            FilterModel<?> model) {
        if (actual != declared) {
            throw new SchemaException(kind + " '" + id + "' is not the handle declared by model '" + model.id() + "'");
        }
    }

    private static JsonNode operand(Object value) {
        return switch (value) {
            case String text -> JSON.getNodeFactory().textNode(text);
            case Integer number -> JSON.getNodeFactory().numberNode(number);
            case Boolean flag -> JSON.getNodeFactory().booleanNode(flag);
            case Pattern pattern -> {
                ObjectNode regex = JSON.createObjectNode();
                regex.put("pattern", pattern.pattern());
                regex.put("flags", pattern.flags());
                yield regex;
            }
            case List<?> values -> {
                ArrayNode array = JSON.createArrayNode();
                values.forEach(element -> array.add(operand(element)));
                yield array;
            }
            default ->
                throw new SchemaException("no wire encoding for an operand of type "
                        + value.getClass().getName());
        };
    }

    private static String wire(Operator operator) {
        return operator.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------- read

    private static <T> FilterExpr<T> expression(
            @org.jspecify.annotations.Nullable JsonNode node, FilterModel<T> model) {
        if (node == null || !node.isObject()) {
            throw new SchemaException("an expression node is missing or not an object");
        }
        String kind = text(node, "node");
        return switch (kind) {
            case "and" -> {
                requireOnly(node, "and node", "node", "operands");
                yield FilterExpr.and(branches(node, model));
            }
            case "or" -> {
                requireOnly(node, "or node", "node", "operands");
                yield FilterExpr.or(branches(node, model));
            }
            case "not" -> {
                requireOnly(node, "not node", "node", "operand");
                yield new FilterExpr.Not<>(expression(node.get("operand"), model));
            }
            case "compare" -> {
                requireOnly(node, "comparison node", "node", "field", "op", "value");
                yield compare(node, model);
            }
            case "to_many" -> {
                requireOnly(node, "to-many node", "node", "relation", "quantifier", "predicate");
                yield toMany(node, model);
            }
            case "to_one" -> {
                requireOnly(node, "to-one node", "node", "relation", "predicate");
                yield toOne(node, model);
            }
            default -> throw new SchemaException("unknown node kind '" + kind + "'");
        };
    }

    private static <T> List<FilterExpr<T>> branches(JsonNode node, FilterModel<T> model) {
        JsonNode operands = node.get("operands");
        if (operands == null || !operands.isArray()) {
            throw new SchemaException("a composition node has no operands array");
        }
        List<FilterExpr<T>> branches = new ArrayList<>();
        operands.forEach(operand -> branches.add(expression(operand, model)));
        return branches;
    }

    private static <T> FilterExpr<T> compare(JsonNode node, FilterModel<T> model) {
        FieldRef<T, ?> field = model.field(text(node, "field"));
        Operator operator = operator(text(node, "op"));
        JsonNode value = node.get("value");
        if (value == null) {
            throw new SchemaException("a comparison has no value");
        }
        try {
            return new FilterExpr.Compare<>(field, operator, value(value, field.kind(), operator));
        } catch (IllegalArgumentException e) {
            throw new SchemaException("invalid comparison: " + e.getMessage());
        }
    }

    private static <T, R> FilterExpr<T> toMany(JsonNode node, FilterModel<T> model) {
        FilterModel.Relation<T, R> relation = cast(model.toMany(text(node, "relation")));
        var handle = relation.toMany();
        if (handle == null) {
            throw new SchemaException("a to-many relation has no handle");
        }
        FilterExpr.Quantifier quantifier = quantifier(text(node, "quantifier"));
        return new FilterExpr.ToMany<>(handle, quantifier, expression(node.get("predicate"), relation.target()));
    }

    private static <T, R> FilterExpr<T> toOne(JsonNode node, FilterModel<T> model) {
        FilterModel.Relation<T, R> relation = cast(model.toOne(text(node, "relation")));
        var handle = relation.toOne();
        if (handle == null) {
            throw new SchemaException("a to-one relation has no handle");
        }
        return new FilterExpr.ToOne<>(handle, expression(node.get("predicate"), relation.target()));
    }

    /**
     * The operand's Java type is decided by the field's kind rather than by what the document
     * happens to contain, so a text field cannot be compared against a number.
     */
    private static Object value(JsonNode value, FieldKind kind, Operator operator) {
        if (operator == Operator.IN) {
            require(value.isArray(), "an 'in' comparison needs an array");
            List<Object> values = new ArrayList<>();
            value.forEach(element -> values.add(scalar(element, kind)));
            return List.copyOf(values);
        }
        if (operator == Operator.MATCHES) {
            require(
                    value.isObject()
                            && value.hasNonNull("pattern")
                            && value.get("pattern").isTextual(),
                    "a regex comparison needs a string pattern");
            requireOnly(value, "regex operand", "pattern", "flags");
            require(!value.has("flags") || value.get("flags").isInt(), "regex flags must be an integer");
            return Pattern.compile(
                    value.get("pattern").asText(), value.path("flags").asInt(0));
        }
        return scalar(value, kind);
    }

    private static Object scalar(JsonNode value, FieldKind kind) {
        return switch (kind) {
            case TEXT -> {
                require(value.isTextual(), "a text field needs a string operand");
                yield value.asText();
            }
            case NUMBER -> {
                require(value.isInt(), "a number field needs an integer operand");
                yield value.asInt();
            }
            case FLAG -> {
                require(value.isBoolean(), "a flag field needs a boolean operand");
                yield value.asBoolean();
            }
        };
    }

    private static Operator operator(String wire) {
        for (Operator operator : Operator.values()) {
            if (wire(operator).equals(wire)) {
                return operator;
            }
        }
        throw new SchemaException("unknown operator '" + wire + "'");
    }

    private static FilterExpr.Quantifier quantifier(String wire) {
        for (FilterExpr.Quantifier quantifier : FilterExpr.Quantifier.values()) {
            if (quantifier.name().toLowerCase(Locale.ROOT).equals(wire)) {
                return quantifier;
            }
        }
        throw new SchemaException("unknown quantifier '" + wire + "'");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new SchemaException("'" + field + "' is missing or not a string");
        }
        return value.asText();
    }

    private static void requireOnly(JsonNode node, String what, String... allowedNames) {
        Set<String> allowed = Set.of(allowedNames);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new SchemaException(what + " has unknown property '" + name + "'");
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T, R> FilterModel.Relation<T, R> cast(FilterModel.Relation<T, ?> relation) {
        return (FilterModel.Relation<T, R>) relation;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new SchemaException(message);
        }
    }
}
