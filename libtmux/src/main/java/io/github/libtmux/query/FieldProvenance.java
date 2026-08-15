package io.github.libtmux.query;

/**
 * Whether a field's accessor is the library's own canonical read of a backend field, or something a
 * caller supplied.
 *
 * <p>Pushdown eligibility depends on this and it must not be caller-declarable. A derived accessor
 * is an opaque lambda: {@code Fields.text("session_name", r -> r.text("session_name").toLowerCase())}
 * lowers to a filter on {@code #{session_name}} that reads exact while answering a different
 * question, and no compiler can inspect the lambda to notice.
 *
 * <p>{@link Canonical} is therefore unforgeable from outside this package — its constructor is
 * private and its only instance is package-private — so a caller cannot assert canonical semantics
 * for an accessor the library did not write.
 */
public sealed interface FieldProvenance permits FieldProvenance.Canonical, FieldProvenance.Derived {

    /** True when a lowering compiler may treat the field name as authoritative for its accessor. */
    boolean lowerable();

    /** Minted only by the library's own metamodel. */
    final class Canonical implements FieldProvenance {
        static final Canonical INSTANCE = new Canonical();

        private Canonical() {}

        @Override
        public boolean lowerable() {
            return true;
        }

        @Override
        public String toString() {
            return "canonical";
        }
    }

    /** Anything a caller built. Always local-only. */
    final class Derived implements FieldProvenance {
        public static final Derived INSTANCE = new Derived();

        private Derived() {}

        @Override
        public boolean lowerable() {
            return false;
        }

        @Override
        public String toString() {
            return "derived";
        }
    }
}
