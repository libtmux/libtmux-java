package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;
import io.github.libtmux.exception.CardinalityException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.query.Fields;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.Selections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Filtering a real hierarchy with expressions rather than lambdas.
 *
 * <p>The point of an expression over a lambda is that it survives being written down: it goes into a
 * stream unchanged, and it can also be printed or translated. These cases check the first half
 * against real tmux, and that filtering never issues a command of its own.
 */
@ExtendWith(TmuxExtension.class)
final class FilteringIntegrationTest {

    @Test
    void anExpressionDropsStraightIntoAStream(Server server) {
        server.sessions().get(0).newWindow("editor");
        server.sessions().get(0).newWindow("logs");

        List<String> editors = server.windows().stream()
                .filter(Window_.name().startsWith("edit"))
                .map(Window::name)
                .toList();

        assertEquals(List.of("editor"), editors);
    }

    /**
     * A pane can be picked out by what a capture already carries — its title, its size, where it
     * sits — without a round trip per pane. Only id, command, index and activity could be named
     * before.
     */
    @Test
    void aPaneIsFoundByItsTitleSizeAndPlace(Server server) {
        Window window = server.sessions().get(0).windows().get(0);
        window.resizeTo(new io.github.libtmux.Dimensions(120, 40));
        Pane right = window.refresh().panes().get(0).split(spec -> spec.toRight());
        var unused = right.retitle("logs");

        List<Pane> panes = server.panes();

        assertEquals(
                List.of(right.id()),
                panes.stream().filter(Pane_.title().is("logs")).map(Pane::id).toList());
        assertEquals(
                List.of(right.id()),
                panes.stream()
                        .filter(Pane_.left().greaterThan(0).and(Pane_.width().atMost(60)))
                        .map(Pane::id)
                        .toList(),
                "the right half starts past column 0 and is at most half as wide");
        assertTrue(panes.stream().allMatch(Pane_.top().is(0)), "a side-by-side split keeps both at the top");
        assertTrue(panes.stream().noneMatch(Pane_.path().is("")), "every pane reports a directory");
    }

    @Test
    void expressionsComposeWithAndOrAndNot(Server server) {
        server.sessions().get(0).newWindow("editor");

        FilterExpr<Window> namedEditor = Window_.name().is("editor");
        FilterExpr<Window> notActive = Window_.active().isFalse();

        assertEquals(
                1,
                server.windows().stream()
                        .filter(namedEditor.and(notActive.negate()))
                        .count(),
                "the window just created is the active one");
        assertEquals(
                2, server.windows().stream().filter(namedEditor.or(notActive)).count());
    }

    @Test
    void aRelationAsksAboutChildrenWithoutLeavingTheCapture(Server server) {
        Session session = server.sessions().get(0);
        session.newWindow("editor").split();

        FilterExpr<Session> hasASplitWindow =
                Session_.windows().any(Window_.panes().any(Pane_.index().is(1)));

        assertTrue(hasASplitWindow.test(server.sessions().get(0)));
        assertTrue(
                Session_.windows()
                        .all(Window_.name().isNot("nothing-is-called-this"))
                        .test(session),
                "every window satisfies a predicate none of them violate");
    }

    /** Vacuous truth is covered as a unit; this checks the quantifiers against real children. */
    @Test
    void quantifiersReadRealChildren(Server server) {
        Window window = server.sessions().get(0).windows().get(0);

        assertTrue(!Window_.panes().any(Pane_.index().is(99)).test(window), "no pane has index 99");
        assertTrue(!Window_.panes().all(Pane_.index().is(99)).test(window), "and not every pane does either");
        assertTrue(Window_.panes().none(Pane_.index().is(99)).test(window));
        assertTrue(Window_.panes().any(Pane_.active().isTrue()).test(window), "a window has an active pane");
    }

    @Test
    void cardinalityIsExplicitAboutZeroAndMany(Server server) {
        server.sessions().get(0).newWindow("only");

        List<Window> matches =
                server.windows().stream().filter(Window_.name().is("only")).toList();

        assertEquals("only", Selections.exactlyOne(matches).name());
        assertThrows(
                CardinalityException.NoMatch.class,
                () -> Selections.exactlyOne(server.windows().stream()
                        .filter(Window_.name().is("absent"))
                        .toList()),
                "zero matches and several matches are different bugs in a caller");
        assertThrows(
                CardinalityException.MultipleMatches.class,
                () -> Selections.exactlyOne(server.windows().stream().toList()),
                "two windows is not one window");
    }

    /** A lookup answers one or none, and counts exactly when it is ambiguous. */
    @Test
    void aLookupByExpressionIsOneOrNone(Server server) {
        server.newSession("alpha");
        server.newSession("alpha-2");
        server.newSession("alpha-3");

        assertEquals(
                "alpha",
                server.session(Session_.name().is("alpha")).orElseThrow().name());
        assertEquals(Optional.empty(), server.session(Session_.name().is("absent")));
        CardinalityException.MultipleMatches ambiguous = assertThrows(
                CardinalityException.MultipleMatches.class,
                () -> server.session(Session_.name().startsWith("alpha")));
        assertEquals(3, ambiguous.atLeast(), "a lookup holds its whole capture, so the count is exact");
        assertTrue(String.valueOf(ambiguous.getMessage()).contains("alpha"), ambiguous.getMessage());

        Pane only = server.session(Session_.name().is("alpha-2"))
                .orElseThrow()
                .windows()
                .get(0)
                .panes()
                .get(0);
        assertEquals(
                only.id(),
                server.pane(Pane_.id().is(only.id().value())).orElseThrow().id());
        assertEquals(Optional.empty(), server.window(Window_.name().is("no-such-window")));
    }

    @Test
    void filteringNeverShellsOut(Server server) {
        server.sessions().get(0).newWindow("editor");
        List<Window> captured = server.windows();
        Window doomed = captured.stream()
                .filter(Window_.name().is("editor"))
                .findFirst()
                .orElseThrow();
        doomed.kill();

        List<Pane> stillThere = captured.stream()
                .filter(Window_.name().is("editor"))
                .flatMap(window -> window.panes().stream())
                .filter(Pane_.active().isTrue())
                .toList();

        assertEquals(1, stillThere.size(), "a filter reads the capture it was given, not the live server");
    }

    @Test
    void anExpressionSaysWhatItIs(Server server) {
        FilterExpr<Window> expression =
                Window_.name().startsWith("edit").and(Window_.active().isTrue());

        String rendered = expression.describe();

        assertTrue(rendered.contains("window_name"), "a lambda could not say this: " + rendered);
        assertTrue(rendered.contains("window_active"), rendered);
    }

    /**
     * A caller's accessor may carry a built-in field's name and answer a different question, so
     * local evaluation must use the accessor rather than infer meaning from the name.
     */
    @Test
    void aCallerBuiltFieldIsAnsweredHereRatherThanByTmux(Server server) {
        List<Pane> panes = server.panes();
        String running = panes.get(0).currentCommand();
        Fields.TextField<Pane> prefixed =
                Fields.text("pane_current_command", (Pane pane) -> "shell-" + pane.currentCommand());

        assertEquals(1, panes.stream().filter(prefixed.is("shell-" + running)).count());
        assertEquals(
                0,
                panes.stream().filter(Pane_.command().is("shell-" + running)).count(),
                "the built-in field of the same name answers differently over these rows");
    }
}
