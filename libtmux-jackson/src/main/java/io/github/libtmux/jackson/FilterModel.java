package io.github.libtmux.jackson;

import io.github.libtmux.query.FieldRef;
import io.github.libtmux.query.Fields;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What a document's field and relation ids mean.
 *
 * <p>Expressions carry executable field and relation handles, while documents carry only their ids.
 * This model binds those forms and gives each nested relation its target model.
 *
 * @param <T> the entity this model describes
 */
public final class FilterModel<T> {

    private static final Set<String> BUILT_IN_IDS = Set.of("pane", "window", "session", "client");

    private final String id;
    private final Map<String, FieldRef<T, ?>> fields;
    private final Map<String, Relation<T, ?>> toMany;
    private final Map<String, Relation<T, ?>> toOne;

    private FilterModel(Builder<T> builder) {
        this.id = builder.id;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.fields));
        this.toMany = Collections.unmodifiableMap(new LinkedHashMap<>(builder.toMany));
        this.toOne = Collections.unmodifiableMap(new LinkedHashMap<>(builder.toOne));
    }

    /** Starts a caller-owned model. Its id must identify the caller's namespace. */
    public static <T> Builder<T> named(String id) {
        String checked = requireId(id);
        if (BUILT_IN_IDS.contains(checked)) {
            throw new IllegalArgumentException("model id '" + checked + "' is reserved by libtmux");
        }
        if (!isNamespaced(checked)) {
            throw new IllegalArgumentException(
                    "custom model id '" + checked + "' must be namespaced, for example 'example/editor'");
        }
        return new Builder<>(checked);
    }

    /** Starts a caller-owned model without requiring a generic type witness. */
    public static <T> Builder<T> named(String id, Class<T> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        return named(id);
    }

    static <T> Builder<T> builtIn(String id) {
        String checked = requireId(id);
        if (!BUILT_IN_IDS.contains(checked)) {
            throw new IllegalArgumentException("unknown libtmux model id '" + checked + "'");
        }
        return new Builder<>(checked);
    }

    /** The id documents name this model by. */
    public String id() {
        return id;
    }

    /** Every field a document may compare on, in declaration order. */
    public Set<String> fieldNames() {
        return fields.keySet();
    }

    /** Every relation a document may navigate. */
    public Set<String> relationNames() {
        Set<String> names = new LinkedHashSet<>(toOne.keySet());
        names.addAll(toMany.keySet());
        return Collections.unmodifiableSet(names);
    }

    FieldRef<T, ?> field(String fieldId) {
        FieldRef<T, ?> field = fields.get(fieldId);
        if (field == null) {
            throw new SchemaException("model '" + id + "' has no field '" + fieldId + "'");
        }
        return field;
    }

    Relation<T, ?> toMany(String relation) {
        return require(toMany, relation, "to-many relation");
    }

    Relation<T, ?> toOne(String relation) {
        return require(toOne, relation, "to-one relation");
    }

    private Relation<T, ?> require(Map<String, Relation<T, ?>> known, String relation, String what) {
        Relation<T, ?> found = known.get(relation);
        if (found == null) {
            throw new SchemaException("model '" + id + "' has no " + what + " '" + relation + "'");
        }
        return found;
    }

    /** A relation handle paired with the model its far side is described by. */
    record Relation<T, R>(
            Fields.@Nullable ToManyRef<T, R> toMany,
            Fields.@Nullable ToOneRef<T, R> toOne,
            Supplier<? extends FilterModel<R>> targetModel) {

        Relation {
            Objects.requireNonNull(targetModel, "targetModel");
        }

        FilterModel<R> target() {
            return Objects.requireNonNull(targetModel.get(), "relation target model");
        }
    }

    /** Collects the exact handles and unique ids a document may name. */
    public static final class Builder<T> {

        private final String id;
        private final Map<String, FieldRef<T, ?>> fields = new LinkedHashMap<>();
        private final Map<String, Relation<T, ?>> toMany = new LinkedHashMap<>();
        private final Map<String, Relation<T, ?>> toOne = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        /** Declares a text field by the handle a metamodel already mints. */
        public Builder<T> field(Fields.TextField<T> field) {
            return add(field.ref());
        }

        /** Declares a number field. */
        public Builder<T> field(Fields.NumberField<T> field) {
            return add(field.ref());
        }

        /** Declares a flag field. */
        public Builder<T> field(Fields.FlagField<T> field) {
            return add(field.ref());
        }

        /** Declares a to-many relation and the model describing what it reaches. */
        public <R> Builder<T> toMany(Fields.ToManyRef<T, R> relation, FilterModel<R> target) {
            Objects.requireNonNull(target, "target");
            return toMany(relation, () -> target);
        }

        /** Declares a to-many relation whose target closes a recursive model graph. */
        public <R> Builder<T> toMany(Fields.ToManyRef<T, R> relation, Supplier<? extends FilterModel<R>> target) {
            Objects.requireNonNull(relation, "relation");
            requireAvailable(relation.id());
            toMany.put(relation.id(), new Relation<>(relation, null, target));
            return this;
        }

        /** Declares a to-one relation and the model describing what it reaches. */
        public <R> Builder<T> toOne(Fields.ToOneRef<T, R> relation, FilterModel<R> target) {
            Objects.requireNonNull(target, "target");
            return toOne(relation, () -> target);
        }

        /** Declares a to-one relation whose target closes a recursive model graph. */
        public <R> Builder<T> toOne(Fields.ToOneRef<T, R> relation, Supplier<? extends FilterModel<R>> target) {
            Objects.requireNonNull(relation, "relation");
            requireAvailable(relation.id());
            toOne.put(relation.id(), new Relation<>(null, relation, target));
            return this;
        }

        private Builder<T> add(FieldRef<T, ?> ref) {
            Objects.requireNonNull(ref, "field");
            requireAvailable(ref.id());
            fields.put(ref.id(), ref);
            return this;
        }

        private void requireAvailable(String member) {
            if (fields.containsKey(member) || toMany.containsKey(member) || toOne.containsKey(member)) {
                throw new IllegalArgumentException("model '" + id + "' already declares '" + member + "'");
            }
        }

        /** Builds the model. */
        public FilterModel<T> build() {
            return new FilterModel<>(this);
        }
    }

    private static String requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("model id must not be blank or contain whitespace");
        }
        return id;
    }

    private static boolean isNamespaced(String id) {
        for (char separator : new char[] {'/', '.', ':'}) {
            int position = id.indexOf(separator);
            if (position > 0 && position < id.length() - 1) {
                return true;
            }
        }
        return false;
    }
}
