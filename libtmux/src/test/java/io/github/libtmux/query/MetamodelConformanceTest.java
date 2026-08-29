package io.github.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.Client_;
import io.github.libtmux.Pane_;
import io.github.libtmux.Session_;
import io.github.libtmux.Window_;
import io.github.libtmux.query.Model.Pane;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The metamodel drift guard, and proof that it can fail. */
final class MetamodelConformanceTest {

    @Test
    void theHandWrittenMetamodelsConform() {
        MetamodelConformance.assertConformant(Model.Pane_.class, Set.of("command", "index", "active"), false);
        MetamodelConformance.assertConformant(Model.Window_.class, Set.of("name"), false);
        MetamodelConformance.assertConformant(
                Pane_.class, Set.of("pane_id", "pane_current_command", "pane_index", "pane_active"), false);
        MetamodelConformance.assertConformant(
                Window_.class,
                Set.of("window_id", "window_name", "window_index", "window_active", "window_linked"),
                false);
        MetamodelConformance.assertConformant(
                Session_.class, Set.of("session_id", "session_name", "session_attached"), false);
        MetamodelConformance.assertConformant(Client_.class, Set.of("client_name"), false);
    }

    /** Two handles, one identifier — the other mistake handwriting invites. */
    static final class Duplicated {
        static Fields.TextField<Pane> command() {
            return Fields.text("command", Pane::command);
        }

        static Fields.TextField<Pane> alias() {
            return Fields.text("command", Pane::command);
        }

        private Duplicated() {}
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
