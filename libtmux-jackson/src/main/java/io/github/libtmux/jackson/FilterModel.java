package io.github.libtmux.jackson;

import io.github.libtmux.query.FieldRef;
import io.github.libtmux.query.Fields;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What a document's field and relation ids mean.
 *
 * <p>An expression carries accessors and navigators — plain functions — which no document can hold.
 * Reading one back therefore needs somewhere to look those up, and that is this. It is also what
 * makes a document typed: a document claiming model {@code pane} cannot be read as a
 * {@code FilterExpr<Session>}, because the ids simply are not there.
 *
 * @param <T> the entity this model describes
 */
public final class FilterModel<T> {

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

    /** Starts a model with the id documents will name it by. */
    public static <T> Builder<T> named(String id) {
        return new Builder<>(id);
    }

    /** The id documents name this model by. */
    public String id() {
        return id;
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

    /** A relation's navigator paired with the model its far side is described by. */
    record Relation<T, R>(
            @Nullable Function<T, List<R>> toMany, @Nullable Function<T, Optional<R>> toOne, FilterModel<R> target) {}

    /** Collects the ids a document may name. */
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
            toMany.put(relation.name(), new Relation<>(relation.navigate(), null, target));
            return this;
        }

        /** Declares a to-one relation and the model describing what it reaches. */
        public <R> Builder<T> toOne(Fields.ToOneRef<T, R> relation, FilterModel<R> target) {
            toOne.put(relation.name(), new Relation<>(null, relation.navigate(), target));
            return this;
        }

        private Builder<T> add(FieldRef<T, ?> ref) {
            fields.put(ref.id(), ref);
            return this;
        }

        /** Builds the model. */
        public FilterModel<T> build() {
            return new FilterModel<>(this);
        }
    }
}
