package io.github.libtmux.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Client;
import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;
import io.github.libtmux.query.Fields;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
                FilterJson.readString(FilterJson.writeString(original, LibTmuxModels.pane()), LibTmuxModels.pane());

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
                    FilterJson.readString(FilterJson.writeString(original, LibTmuxModels.pane()), LibTmuxModels.pane());
            assertEquals(original.describe(), restored.describe(), "round trip changed " + original.describe());
        }
    }

    @Test
    void comparisonOperandsAreImmutableAndRoundTripByValue() {
        List<String> mutable = new ArrayList<>(List.of("zsh"));
        FilterExpr.Compare<Pane, String> membership =
                new FilterExpr.Compare<>(Pane_.command().ref(), Operator.IN, mutable);
        mutable.add("bash");

        assertEquals(List.of("zsh"), membership.operand());
        assertEquals(
                membership,
                FilterJson.readString(FilterJson.writeString(membership, LibTmuxModels.pane()), LibTmuxModels.pane()));

        FilterExpr.Compare<Pane, String> setMembership =
                new FilterExpr.Compare<>(Pane_.command().ref(), Operator.IN, Set.of("zsh", "bash"));
        assertEquals(
                setMembership,
                FilterJson.readString(
                        FilterJson.writeString(setMembership, LibTmuxModels.pane()), LibTmuxModels.pane()));

        FilterExpr<Pane> regex = Pane_.command().matches(Pattern.compile("^z", Pattern.CASE_INSENSITIVE));
        assertEquals(
                regex,
                FilterJson.readString(FilterJson.writeString(regex, LibTmuxModels.pane()), LibTmuxModels.pane()));
    }

    @Test
    void bothRelationKindsSurviveTheRoundTrip() {
        FilterExpr<Window> quantified = Window_.panes().none(Pane_.active().isTrue());
        FilterExpr<Window> parent = Window_.session().is(Session_.name().is("build"));
        FilterExpr<Session> nested =
                Session_.windows().any(Window_.panes().all(Pane_.index().atMost(3)));

        assertEquals(
                quantified.describe(),
                FilterJson.readString(
                                FilterJson.writeString(quantified, LibTmuxModels.window()), LibTmuxModels.window())
                        .describe());
        assertEquals(
                parent.describe(),
                FilterJson.readString(FilterJson.writeString(parent, LibTmuxModels.window()), LibTmuxModels.window())
                        .describe());
        assertEquals(
                nested.describe(),
                FilterJson.readString(FilterJson.writeString(nested, LibTmuxModels.session()), LibTmuxModels.session())
                        .describe());

        String json = FilterJson.writeString(nested, LibTmuxModels.session());
        FilterExpr<Session> restored = FilterJson.readString(json, LibTmuxModels.session());
        assertEquals(json, FilterJson.writeString(restored, LibTmuxModels.session()));
    }

    /**
     * The shape coming back is not the point; the filter coming back is. This evaluates a restored
     * expression against real values, over a model small enough to build here.
     */
    @Test
    void aRestoredExpressionFiltersTheSameThings() {
        FilterExpr<Editor> original =
                Editor_.NAME.startsWith("nv").and(Editor_.RANK.atLeast(2)).or(Editor_.PINNED.isTrue());

        FilterExpr<Editor> restored =
                FilterJson.readString(FilterJson.writeString(original, Editor_.MODEL), Editor_.MODEL);

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
    static final class Editor_ {
        static final Fields.TextField<Editor> NAME = Fields.text("name", Editor::name);
        static final Fields.NumberField<Editor> RANK = Fields.number("rank", Editor::rank);
        static final Fields.FlagField<Editor> PINNED = Fields.flag("pinned", Editor::pinned);

        static final FilterModel<Editor> MODEL = FilterModel.named("example/editor", Editor.class)
                .field(NAME)
                .field(RANK)
                .field(PINNED)
                .build();

        private Editor_() {}
    }

    @Test
    void theDocumentNamesItsSchemaAndModel() {
        String json = FilterJson.writeString(Pane_.active().isTrue(), LibTmuxModels.pane());

        assertTrue(json.contains("\"schema\":\"libtmux.filter/1\""), json);
        assertTrue(json.contains("\"model\":\"pane\""), json);
        assertTrue(json.contains("\"field\":\"pane_active\""), "tmux's own format name is the wire id: " + json);
    }

    // ---------------------------------------------------------------------------- failing closed

    /** The point of the format: only fields declared by the supplied model have wire identity. */
    @Test
    void anUndeclaredFieldCannotBeWritten() {
        FilterExpr<Pane> local =
                Fields.<Pane>text("whatever", pane -> pane.currentCommand()).is("zsh");

        SchemaException refused =
                assertThrows(SchemaException.class, () -> FilterJson.writeString(local, LibTmuxModels.pane()));

        assertTrue(
                String.valueOf(refused.getMessage()).contains("model 'pane'"), "the message must name the authority");
    }

    @Test
    void aSameIdFieldFromAnotherMetamodelCannotBeWritten() {
        FilterExpr<Pane> forged = ForgedPane_.COMMAND.is("forged-zsh");

        assertThrows(SchemaException.class, () -> FilterJson.writeString(forged, LibTmuxModels.pane()));
    }

    @Test
    void aNestedFieldOutsideTheModelGraphCannotBeWritten() {
        FilterExpr<Window> forged = Window_.panes().any(ForgedPane_.COMMAND.is("forged-zsh"));

        assertThrows(SchemaException.class, () -> FilterJson.writeString(forged, LibTmuxModels.window()));
    }

    @Test
    void aSameIdRelationWithAnotherNavigatorCannotBeWritten() {
        FilterExpr<Window> forged = Fields.<Window, Pane>toMany("panes", ignored -> List.of())
                .any(Pane_.active().isTrue());

        assertThrows(SchemaException.class, () -> FilterJson.writeString(forged, LibTmuxModels.window()));
    }

    @Test
    void aDifferentRelationHandleCannotBorrowTheDeclaredNavigator() {
        Function<Window, List<Pane>> navigate = Window::panes;
        var declared = Fields.<Window, Pane>toMany("panes", navigate);
        var forged = Fields.<Window, Pane>toMany("panes", navigate);
        FilterModel<Window> model = FilterModel.<Window>named("example/window")
                .toMany(declared, LibTmuxModels.pane())
                .build();

        assertThrows(
                SchemaException.class,
                () -> FilterJson.writeString(forged.any(Pane_.active().isTrue()), model));
    }

    @Test
    void aSameIdToOneRelationWithAnotherNavigatorCannotBeWritten() {
        FilterExpr<Client> forged = Fields.<Client, Session>toOne("session", ignored -> Optional.empty())
                .is(Session_.attached().isTrue());

        assertThrows(SchemaException.class, () -> FilterJson.writeString(forged, LibTmuxModels.client()));
    }

    @Test
    void customModelsCannotClaimBuiltInIds() {
        for (String id : List.of("pane", "window", "session", "client")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FilterModel.<Pane>named(id).field(Pane_.command()).build(),
                    id);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> FilterModel.<Editor>named("editor").field(Editor_.NAME).build(),
                "custom model ids must name their owner");
    }

    @Test
    void blankAndDuplicateModelMembersAreRefused() {
        var children = Fields.<Editor, Editor>toMany("children", ignored -> List.of());
        var parent = Fields.<Editor, Editor>toOne("children", ignored -> Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> FilterModel.<Editor>named(" "));
        assertThrows(IllegalArgumentException.class, () -> Fields.<Editor>text(" ", Editor::name));
        assertThrows(IllegalArgumentException.class, () -> Fields.<Editor, Editor>toMany(" ", ignored -> List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> FilterModel.<Editor>named("example/editor")
                        .field(Editor_.NAME)
                        .field(Editor_.NAME));
        assertThrows(
                IllegalArgumentException.class,
                () -> FilterModel.<Editor>named("example/editor")
                        .toMany(children, Editor_.MODEL)
                        .toMany(children, Editor_.MODEL));
        assertThrows(
                IllegalArgumentException.class,
                () -> FilterModel.<Editor>named("example/editor")
                        .toMany(children, Editor_.MODEL)
                        .toOne(parent, Editor_.MODEL));
    }

    static final class ForgedPane_ {
        static final Fields.TextField<Pane> COMMAND =
                Fields.text("pane_current_command", pane -> "forged-" + pane.currentCommand());

        private ForgedPane_() {}
    }

    @Test
    void anUnknownSchemaVersionIsRefused() {
        String json = FilterJson.writeString(Pane_.active().isTrue(), LibTmuxModels.pane())
                .replace("libtmux.filter/1", "libtmux.filter/99");

        assertThrows(SchemaException.class, () -> FilterJson.readString(json, LibTmuxModels.pane()));
    }

    @Test
    void aDocumentForAnotherModelIsRefused() {
        String json = FilterJson.writeString(Pane_.active().isTrue(), LibTmuxModels.pane());

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
    void anOperatorIncompatibleWithItsFieldIsRefused() {
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_index\",\"op\":\"contains\",\"value\":2}}",
                        LibTmuxModels.pane()));
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
    void blankTrailingAndInvalidRegexDocumentsAreRefusedUniformly() {
        String valid = FilterJson.writeString(Pane_.active().isTrue(), LibTmuxModels.pane());
        assertThrows(SchemaException.class, () -> FilterJson.readString("", LibTmuxModels.pane()));
        assertThrows(SchemaException.class, () -> FilterJson.readString(valid + valid, LibTmuxModels.pane()));
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_current_command\",\"op\":\"matches\","
                                + "\"value\":{\"pattern\":\"[\",\"flags\":0}}}",
                        LibTmuxModels.pane()));
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_current_command\",\"op\":\"matches\","
                                + "\"value\":{\"pattern\":\"x\",\"flags\":2147483647}}}",
                        LibTmuxModels.pane()));
    }

    @Test
    void unknownDocumentAndNodePropertiesAreRefused() {
        List<String> documents = List.of(
                "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"extra\":true,\"expr\":"
                        + "{\"node\":\"compare\",\"field\":\"pane_active\",\"op\":\"equals\",\"value\":true}}",
                "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                        + "{\"node\":\"compare\",\"field\":\"pane_active\",\"op\":\"equals\",\"value\":true,\"extra\":true}}",
                "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                        + "{\"node\":\"compare\",\"field\":\"pane_current_command\",\"op\":\"matches\","
                        + "\"value\":{\"pattern\":\"nv\",\"flags\":0,\"extra\":true}}}");

        for (String document : documents) {
            assertThrows(SchemaException.class, () -> FilterJson.readString(document, LibTmuxModels.pane()));
        }
    }

    @Test
    void duplicatePropertiesAndNonTextRegexPatternsAreRefused() {
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_active\",\"op\":\"equals\",\"value\":true}}",
                        LibTmuxModels.pane()));
        assertThrows(
                SchemaException.class,
                () -> FilterJson.readString(
                        "{\"schema\":\"libtmux.filter/1\",\"model\":\"pane\",\"expr\":"
                                + "{\"node\":\"compare\",\"field\":\"pane_current_command\",\"op\":\"matches\","
                                + "\"value\":{\"pattern\":1}}}",
                        LibTmuxModels.pane()));
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
