package io.github.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.query.Model.Pane;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The metamodel drift guard, and proof that it can fail. */
final class MetamodelConformanceTest {

    @Test
    void theHandWrittenMetamodelsConform() {
        MetamodelConformance.assertConformant(Model.Pane_.class, Set.of("command", "index", "active"), false);
        MetamodelConformance.assertConformant(Model.Window_.class, Set.of("name"), false);
    }

    /** A metamodel that bypasses canonical minting is the drift a generator would have prevented. */
    static final class Drifted {
        static Fields.TextField<Pane> command() {
            // The mistake: a plain builder, so the field is derived and silently not pushdown-eligible.
            return Fields.text("command", Pane::command);
        }

        private Drifted() {}
    }

    /** Two handles, one identifier — the other mistake handwriting invites. */
    static final class Duplicated extends EntityMetamodel {
        static Fields.TextField<Pane> command() {
            return text("command", Pane::command);
        }

        static Fields.TextField<Pane> alias() {
            return text("command", Pane::command);
        }

        private Duplicated() {}
    }

    @Test
    void theGuardRejectsANonCanonicalHandle() {
        assertThrows(
                AssertionError.class,
                () -> MetamodelConformance.assertConformant(Drifted.class, Set.of("command"), false),
                "a derived field must not pass as a metamodel handle");
    }

    @Test
    void theGuardRejectsADuplicateFieldId() {
        assertThrows(
                AssertionError.class,
                () -> MetamodelConformance.assertConformant(Duplicated.class, Set.of("command"), false));
    }

    @Test
    void theGuardRejectsDriftedCoverage() {
        assertThrows(
                AssertionError.class,
                () -> MetamodelConformance.assertConformant(
                        Model.Pane_.class, Set.of("command", "index", "active", "renamed"), false));
    }
}
