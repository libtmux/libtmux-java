package io.github.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.query.Model.Pane;
import io.github.libtmux.query.Model.Pane_;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The edge parser turns untrusted strings into the same expressions, or refuses. */
final class LegacyFiltersTest {

    private static final LegacyFilters.FieldCatalog<Pane> CATALOG = LegacyFilters.FieldCatalog.<Pane>builder()
            .add("command", Pane_.command())
            .add("index", Pane_.index())
            .add("active", Pane_.active())
            .build();

    private static final List<Pane> PANES = List.of(
            new Pane("%1", "nvim", 0, true), new Pane("%2", "zsh", 1, false), new Pane("%3", "nvtop", 2, false));

    @Test
    void itBuildsTheSameExpressionTheTypedFormWould() {
        FilterExpr<Pane> parsed = CATALOG.parse(Map.of("command__startswith", "nv"));

        assertEquals("(command starts-with nv)", parsed.describe());
        assertEquals(
                List.of("%1", "%3"), PANES.stream().filter(parsed).map(Pane::id).toList());
    }

    @Test
    void severalFiltersConjoinInOrder() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("command__startswith", "nv");
        filters.put("index__gte", "1");

        FilterExpr<Pane> parsed = CATALOG.parse(filters);

        assertEquals("(command starts-with nv and index >= 1)", parsed.describe());
        assertEquals(List.of("%3"), PANES.stream().filter(parsed).map(Pane::id).toList());
    }

    @Test
    void aBareFieldNameMeansEquality() {
        assertEquals("(command == zsh)", CATALOG.parse(Map.of("command", "zsh")).describe());
    }

    @Test
    void flagsAcceptBothSpellings() {
        assertTrue(CATALOG.parse(Map.of("active", "true")).test(PANES.get(0)));
        assertTrue(CATALOG.parse(Map.of("active", "1")).test(PANES.get(0)));
        assertTrue(CATALOG.parse(Map.of("active", "0")).test(PANES.get(1)));
    }

    @Test
    void anEmptyFilterSetMatchesEverything() {
        FilterExpr<Pane> parsed = CATALOG.parse(Map.of());

        assertEquals(3, PANES.stream().filter(parsed).count(), "an empty conjunction is the identity");
    }

    /** Every rejection here is a compile error in the typed form; at the edge it must be a throw. */
    @Test
    void itRefusesWhatTheTypedFormWouldNotCompile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CATALOG.parse(Map.of("index__startswith", "1")),
                "a text operator on a number field");
        assertThrows(
                IllegalArgumentException.class,
                () -> CATALOG.parse(Map.of("command__gte", "3")),
                "an ordering operator on a text field");
        assertThrows(
                IllegalArgumentException.class,
                () -> CATALOG.parse(Map.of("active__contains", "x")),
                "a text operator on a flag field");
        assertThrows(
                IllegalArgumentException.class,
                () -> CATALOG.parse(Map.of("nosuchfield", "x")),
                "an unknown field is refused rather than guessed");
        assertThrows(
                IllegalArgumentException.class,
                () -> CATALOG.parse(Map.of("command__nosuchop", "x")),
                "an unknown operator");
        assertThrows(
                IllegalArgumentException.class,
                () -> CATALOG.parse(Map.of("index__gte", "notanumber")),
                "a value that is not of the field's type");
    }
}
