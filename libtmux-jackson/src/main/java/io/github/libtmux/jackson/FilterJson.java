package io.github.libtmux.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.query.FieldKind;
import io.github.libtmux.query.FieldProvenance;
import io.github.libtmux.query.FieldRef;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The versioned wire form of a filter expression.
 *
 * <p>Writing needs no model: the expression already carries the ids. Reading does, because an
 * expression holds accessors and navigators that no document can carry, and because a document
 * claiming one model must not be read as another.
 *
 * <p>Everything unrecognised fails. An expression read wrongly does not announce itself — it
 * silently matches the wrong things, and a caller who wanted a filter gets one, just not theirs.
 */
public final class FilterJson {

    /** The schema this version reads and writes. Immutable once published. */
    public static final String SCHEMA = "libtmux.filter/1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private FilterJson() {}

    /**
     * Writes an expression as a document naming the model it filters.
     *
     * @throws SchemaException if the expression uses a field built from a lambda, which has a
     *     caller-chosen name and an accessor nobody else can resolve, so it has no wire identity
     */
    public static ObjectNode write(FilterExpr<?> expression, String modelId) {
        ObjectNode document = JSON.createObjectNode();
        document.put("schema", SCHEMA);
        document.put("model", modelId);
        document.set("expr", node(expression));
        return document;
    }

    /** Writes an expression as compact JSON text. */
    public static String writeString(FilterExpr<?> expression, String modelId) {
        return write(expression, modelId).toString();
    }

    /**
     * Reads a document back as an expression over the given model.
     *
     * @throws SchemaException if the schema version, the model, a field, a relation, an operator or
     *     a node shape is not one this version knows
     */
    public static <T> FilterExpr<T> read(JsonNode document, FilterModel<T> model) {
        require(document.isObject(), "the document is not an object");
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
            return read(JSON.readTree(json), model);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new SchemaException("the document is not readable JSON: " + e.getOriginalMessage());
        }
    }

    // ------------------------------------------------------------------------------------ write

    private static ObjectNode node(FilterExpr<?> expression) {
        ObjectNode node = JSON.createObjectNode();
        switch (expression) {
            case FilterExpr.And<?> and -> {
                node.put("node", "and");
                node.set("operands", operands(and.operands()));
            }
            case FilterExpr.Or<?> or -> {
                node.put("node", "or");
                node.set("operands", operands(or.operands()));
            }
            case FilterExpr.Not<?> not -> {
                node.put("node", "not");
                node.set("operand", node(not.operand()));
            }
            case FilterExpr.Compare<?, ?> compare -> {
                FieldRef<?, ?> field = compare.field();
                if (!(field.provenance() instanceof FieldProvenance.Canonical)) {
                    throw new SchemaException(
                            "field '" + field.id() + "' was built from a lambda and has no wire identity");
                }
                node.put("node", "compare");
                node.put("field", field.id());
                node.put("op", wire(compare.operator()));
                node.set("value", operand(compare.operand()));
            }
            case FilterExpr.ToMany<?, ?> toMany -> {
                node.put("node", "to_many");
                node.put("relation", toMany.relation());
                node.put("quantifier", toMany.quantifier().name().toLowerCase(Locale.ROOT));
                node.set("predicate", node(toMany.predicate()));
            }
            case FilterExpr.ToOne<?, ?> toOne -> {
                node.put("node", "to_one");
                node.put("relation", toOne.relation());
                node.set("predicate", node(toOne.predicate()));
            }
        }
        return node;
    }

    private static ArrayNode operands(List<? extends FilterExpr<?>> expressions) {
        ArrayNode array = JSON.createArrayNode();
        expressions.forEach(operand -> array.add(node(operand)));
        return array;
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
            case "and" -> FilterExpr.and(branches(node, model));
            case "or" -> FilterExpr.or(branches(node, model));
            case "not" -> new FilterExpr.Not<>(expression(node.get("operand"), model));
            case "compare" -> compare(node, model);
            case "to_many" -> toMany(node, model);
            case "to_one" -> toOne(node, model);
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
        return new FilterExpr.Compare<>(field, operator, value(value, field.kind(), operator));
    }

    private static <T, R> FilterExpr<T> toMany(JsonNode node, FilterModel<T> model) {
        FilterModel.Relation<T, R> relation = cast(model.toMany(text(node, "relation")));
        var navigate = relation.toMany();
        if (navigate == null) {
            throw new SchemaException("a to-many relation has no navigator");
        }
        FilterExpr.Quantifier quantifier = quantifier(text(node, "quantifier"));
        return new FilterExpr.ToMany<>(
                text(node, "relation"), navigate, quantifier, expression(node.get("predicate"), relation.target()));
    }

    private static <T, R> FilterExpr<T> toOne(JsonNode node, FilterModel<T> model) {
        FilterModel.Relation<T, R> relation = cast(model.toOne(text(node, "relation")));
        var navigate = relation.toOne();
        if (navigate == null) {
            throw new SchemaException("a to-one relation has no navigator");
        }
        return new FilterExpr.ToOne<>(
                text(node, "relation"), navigate, expression(node.get("predicate"), relation.target()));
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
            require(value.isObject() && value.hasNonNull("pattern"), "a regex comparison needs a pattern");
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
