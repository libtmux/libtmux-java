package io.github.libtmux.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code name__contains=dev} form, confined to the edge.
 *
 * <p>Python libtmux accepts filters as keyword arguments whose names encode the operator. That shape
 * cannot survive into the typed API: the field and the operator arrive as untrusted strings, so
 * nothing about their pairing can be checked before the program runs. This parser exists so callers
 * carrying such strings — a CLI flag, a config file, a stored query — have one supported way in, and
 * so the rest of the library never has to accept them.
 *
 * <p>Everything it rejects is a compile error in the typed form. That is the trade being made
 * explicit rather than hidden: one place where a wrong field name or a text operator on a number
 * becomes a runtime failure, and the type system everywhere else.
 */
public final class LegacyFilters {

    private LegacyFilters() {}

    /**
     * Parses {@code field__operator} keys into a conjunction, in the map's iteration order.
     *
     * @param catalog the fields a caller is permitted to name
     * @throws IllegalArgumentException on an unknown field, an unknown operator, or an operator the
     *     field's type does not support
     */
    public static <T> FilterExpr<T> parse(Map<String, String> filters, FieldCatalog<T> catalog) {
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(catalog, "catalog");
        List<FilterExpr<T>> operands = new ArrayList<>(filters.size());
        filters.forEach((key, value) -> operands.add(single(key, value, catalog)));
        return FilterExpr.and(operands);
    }

    private static <T> FilterExpr<T> single(String key, String value, FieldCatalog<T> catalog) {
        int split = key.lastIndexOf("__");
        String fieldName = split < 0 ? key : key.substring(0, split);
        String operator = split < 0 ? "exact" : key.substring(split + 2);

        Object field = catalog.fields().get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("unknown field: " + fieldName);
        }
        if (field instanceof Fields.TextField<?> text) {
            @SuppressWarnings("unchecked")
            Fields.TextField<T> typed = (Fields.TextField<T>) text;
            return switch (operator) {
                case "exact" -> typed.is(value);
                case "contains" -> typed.contains(value);
                case "startswith" -> typed.startsWith(value);
                case "endswith" -> typed.endsWith(value);
                default ->
                    throw new IllegalArgumentException(
                            "operator " + operator + " does not apply to text field " + fieldName);
            };
        }
        if (field instanceof Fields.NumberField<?> number) {
            @SuppressWarnings("unchecked")
            Fields.NumberField<T> typed = (Fields.NumberField<T>) number;
            int operand = parseNumber(value, fieldName);
            return switch (operator) {
                case "exact" -> typed.is(operand);
                case "lt" -> typed.lessThan(operand);
                case "lte" -> typed.atMost(operand);
                case "gt" -> typed.greaterThan(operand);
                case "gte" -> typed.atLeast(operand);
                default ->
                    throw new IllegalArgumentException(
                            "operator " + operator + " does not apply to number field " + fieldName);
            };
        }
        if (field instanceof Fields.FlagField<?> flag) {
            @SuppressWarnings("unchecked")
            Fields.FlagField<T> typed = (Fields.FlagField<T>) flag;
            if (!operator.equals("exact")) {
                throw new IllegalArgumentException(
                        "operator " + operator + " does not apply to flag field " + fieldName);
            }
            return switch (value) {
                case "1", "true" -> typed.isTrue();
                case "0", "false" -> typed.isFalse();
                default -> throw new IllegalArgumentException("not a flag value: " + value);
            };
        }
        throw new IllegalArgumentException("unsupported field kind for " + fieldName);
    }

    private static int parseNumber(String value, String fieldName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number for field " + fieldName + ": " + value, e);
        }
    }

    /**
     * The fields a legacy caller may name.
     *
     * <p>Built through typed handles, so a catalog cannot be assembled from fields belonging to a
     * different entity, and an identifier the catalog does not know is refused rather than guessed.
     */
    public static final class FieldCatalog<T> {

        private final Map<String, Object> fields;

        private FieldCatalog(Map<String, Object> fields) {
            this.fields = Map.copyOf(fields);
        }

        public static <T> Builder<T> builder() {
            return new Builder<>();
        }

        /**
         * Parses {@code field__operator} keys into a conjunction, in the map's iteration order.
         *
         * @throws IllegalArgumentException on an unknown field, an unknown operator, or an operator
         *     the field's type does not support
         */
        public FilterExpr<T> parse(Map<String, String> filters) {
            return LegacyFilters.parse(filters, this);
        }

        Map<String, Object> fields() {
            return fields;
        }

        /** Collects the handles one entity exposes to legacy callers. */
        public static final class Builder<T> {

            private final Map<String, Object> fields = new LinkedHashMap<>();

            private Builder() {}

            public Builder<T> add(String id, Fields.TextField<T> field) {
                return put(id, field);
            }

            public Builder<T> add(String id, Fields.NumberField<T> field) {
                return put(id, field);
            }

            public Builder<T> add(String id, Fields.FlagField<T> field) {
                return put(id, field);
            }

            private Builder<T> put(String id, Object field) {
                if (fields.putIfAbsent(id, field) != null) {
                    throw new IllegalArgumentException("duplicate field id: " + id);
                }
                return this;
            }

            public FieldCatalog<T> build() {
                return new FieldCatalog<>(fields);
            }
        }
    }
}
