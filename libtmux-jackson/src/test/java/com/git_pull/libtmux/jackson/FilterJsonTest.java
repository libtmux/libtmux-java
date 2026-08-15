package com.git_pull.libtmux.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.Pane;
import com.git_pull.libtmux.Pane_;
import com.git_pull.libtmux.Session;
import com.git_pull.libtmux.Session_;
import com.git_pull.libtmux.Window;
import com.git_pull.libtmux.Window_;
import com.git_pull.libtmux.query.Fields;
import com.git_pull.libtmux.query.FilterExpr;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Writing an expression down and reading it back as the same expression.
 *
 * <p>Every failure mode here is a refusal. An expression read wrongly does not announce itself: a
 * caller who asked for a filter still gets one, just not the filter they wrote down.
 */
final class FilterJsonTest {

    // ------------------------------------------------------------------------------ round trips

    @Test
    void aComparisonSurvivesTheRoundTrip() {
        FilterExpr<Pane> original = Pane_.command().startsWith("nv");

        FilterExpr<Pane> restored =
                FilterJson.readString(FilterJson.writeString(original, "pane"), LibTmuxModels.pane());

        assertEquals(original.describe(), restored.describe());
    }

    @Test
    void everyNodeKindSurvivesTheRoundTrip() {
        List<FilterExpr<Pane>> panes = List.of(
                Pane_.command().is("zsh"),
                Pane_.index().atLeast(2),
                Pane_.active().isTrue(),
                Pane_.command().matches(Pattern.compile("^nv.*", Pattern.CASE_INSENSITIVE)),
                Pane_.command().in(List.of("zsh", "bash")),
                Pane_.command().is("a").and(Pane_.index().is(1)),
                Pane_.command().is("a").or(Pane_.index().is(1)),
                Pane_.command().is("a").negate());

        for (FilterExpr<Pane> original : panes) {
            FilterExpr<Pane> restored =
                    FilterJson.readString(FilterJson.writeString(original, "pane"), LibTmuxModels.pane());
            assertEquals(original.describe(), restored.describe(), "round trip changed " + original.describe());
        }
    }

    @Test
    void bothRelationKindsSurviveTheRoundTrip() {
        FilterExpr<Window> quantified = Window_.panes().none(Pane_.active().isTrue());
        FilterExpr<Session> nested =
                Session_.windows().any(Window_.panes().all(Pane_.index().atMost(3)));

        assertEquals(
                quantified.describe(),
                FilterJson.readString(FilterJson.writeString(quantified, "window"), LibTmuxModels.window())
                        .describe());
        assertEquals(
                nested.describe(),
                FilterJson.readString(FilterJson.writeString(nested, "session"), LibTmuxModels.session())
                        .describe());
    }

    /**
     * The shape coming back is not the point; the filter coming back is. This evaluates a restored
     * expression against real values, over a model small enough to build here.
     */
    @Test
    void aRestoredExpressionFiltersTheSameThings() {
        FilterExpr<Editor> original =
                Editor_.NAME.startsWith("nv").and(Editor_.RANK.atLeast(2)).or(Editor_.PINNED.isTrue());

        FilterExpr<Editor> restored = FilterJson.readString(FilterJson.writeString(original, "editor"), Editor_.MODEL);

        List<Editor> values = List.of(
                new Editor("nvim", 3, false),
                new Editor("nvim", 1, false),
                new Editor("zsh", 9, false),
                new Editor("zsh", 0, true));

        assertEquals(
                values.stream().filter(original).map(Editor::name).toList(),
                values.stream().filter(restored).map(Editor::name).toList(),
                "the restored expression must select exactly what the original did");
        assertEquals(
                List.of("nvim", "zsh"),
                values.stream().filter(restored).map(Editor::name).toList(),
                "and it must select the right things in the first place");
    }

    record Editor(String name, int rank, boolean pinned) {}

    /** A metamodel small enough to reason about, minted the way a generated one is. */
    static final class Editor_ extends com.git_pull.libtmux.query.EntityMetamodel {
        static final Fields.TextField<Editor> NAME = text("name", Editor::name);
        static final Fields.NumberField<Editor> RANK = number("rank", Editor::rank);
        static final Fields.FlagField<Editor> PINNED = flag("pinned", Editor::pinned);

        static final FilterModel<Editor> MODEL = FilterModel.<Editor>named("editor")
                .field(NAME)
                .field(RANK)
                .field(PINNED)
                .build();

        private Editor_() {}
    }

    @Test
    void theDocumentNamesItsSchemaAndModel() {
        String json = FilterJson.writeString(Pane_.active().isTrue(), "pane");

        assertTrue(json.contains("\"schema\":\"libtmux.filter/1\""), json);
        assertTrue(json.contains("\"model\":\"pane\""), json);
        assertTrue(json.contains("\"field\":\"pane_active\""), "tmux's own format name is the wire id: " + json);
    }

    // ---------------------------------------------------------------------------- failing closed

    /** The point of the format: a filter built from a lambda has no identity anyone else can resolve. */
    @Test
    void anExpressionBuiltFromALambdaCannotBeWritten() {
        FilterExpr<Pane> local =
                Fields.<Pane>text("whatever", pane -> pane.currentCommand()).is("zsh");

        SchemaException refused = assertThrows(SchemaException.class, () -> FilterJson.writeString(local, "pane"));

        assertTrue(String.valueOf(refused.getMessage()).contains("lambda"), "the message must say why");
    }

    @Test
    void anUnknownSchemaVersionIsRefused() {
        String json = FilterJson.writeString(Pane_.active().isTrue(), "pane")
                .replace("libtmux.filter/1", "libtmux.filter/99");

        assertThrows(SchemaException.class, () -> FilterJson.readString(json, LibTmuxModels.pane()));
    }

    @Test
    void aDocumentForAnotherModelIsRefused() {
        String json = FilterJson.writeString(Pane_.active().isTrue(), "pane");

        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(json, LibTmuxModels.window()),
                "a pane filter is not a window filter, whatever its shape");
    }

    @Test
    void anUnknownFieldOperatorOrNodeIsRefused() {
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"no_such_field\",\"op\":\"equals\",\"value\":\"x\"}}",
                        LibTmuxModels.pane()));
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_active\",\"op\":\"sounds_like\",\"value\":true}}",
                        LibTmuxModels.pane()));
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":{\"node\":\"teleport\"}}",
                        LibTmuxModels.pane()));
    }

    @Test
    void anOperandOfTheWrongTypeForItsFieldIsRefused() {
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_index\",\"op\":\"equals\",\"value\":\"two\"}}",
                        LibTmuxModels.pane()),
                "a number field compared against a string would evaluate to nothing useful");
    }

    @Test
    void aStructurallyBrokenDocumentIsRefused() {
        assertThrows(SchemaException.class, () -> FilterJson.readString("not json at all", LibTmuxModels.pane()));
        assertThrows(SchemaException.class, () -> FilterJson.readString("[]", LibTmuxModels.pane()));
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\"}", LibTmuxModels.pane()));
    }

    @Test
    void anUnknownRelationIsRefused() {
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"window\",\"expr\":"
                                + "{\"node\":\"to_many\",\"relation\":\"tentacles\",\"quantifier\":\"any\","
                                + "\"predicate\":{\"node\":\"compare\",\"field\":\"pane_active\",\"op\":\"equals\",\"value\":true}}}",
                        LibTmuxModels.window()));
    }

    @Test
    void theSchemaIsPackagedWithTheArtifact() {
        assertTrue(
                FilterJson.class.getResourceAsStream("filter-expr-v1.schema.json") != null,
                "the language-neutral schema ships with the jar that writes the format");
    }
}
