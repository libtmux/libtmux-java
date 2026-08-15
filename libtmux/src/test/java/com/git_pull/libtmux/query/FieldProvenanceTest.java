package com.git_pull.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.query.Model.Pane;
import com.git_pull.libtmux.query.Model.Pane_;
import org.junit.jupiter.api.Test;

/**
 * Pushdown eligibility must not be caller-declarable.
 *
 * <p>The bakeoff measured the hole this closes: a caller-supplied accessor that lowers to a filter on
 * its field name reads exact while answering a different question, and no compiler can inspect the
 * lambda to notice. The fix is that only a declared metamodel can mint a canonical field.
 */
final class FieldProvenanceTest {

    @Test
    void aMetamodelFieldIsCanonicalAndCarriesItsKind() {
        assertTrue(Pane_.command().ref().provenance().lowerable());
        assertEquals(FieldKind.TEXT, Pane_.command().ref().kind());
        assertEquals(FieldKind.NUMBER, Pane_.index().ref().kind());
        assertEquals(FieldKind.FLAG, Pane_.active().ref().kind());
    }

    @Test
    void aCallerBuiltFieldIsDerivedAndNotLowerable() {
        // The exact shape the bakeoff caught: right name, different semantics.
        var shouted = Fields.text("command", (Pane pane) -> pane.command().toUpperCase(java.util.Locale.ROOT));

        assertFalse(
                shouted.ref().provenance().lowerable(),
                "a caller's accessor must never be trusted to match its field name");
        assertEquals(FieldKind.TEXT, shouted.ref().kind());
    }

    @Test
    void derivedFieldsStillEvaluateLocally() {
        var shouted = Fields.text("command", (Pane pane) -> pane.command().toUpperCase(java.util.Locale.ROOT));

        assertTrue(shouted.is("NVIM").test(new Pane("%1", "nvim", 0, true)));
        assertFalse(Pane_.command().is("NVIM").test(new Pane("%1", "nvim", 0, true)));
    }
}
