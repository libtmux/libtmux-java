package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.Window_;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.query.Fields;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.Selections;
import java.util.List;
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
                Selections.NoMatchException.class,
                () -> Selections.exactlyOne(server.windows().stream()
                        .filter(Window_.name().is("absent"))
                        .toList()),
                "zero matches and several matches are different bugs in a caller");
        assertThrows(
                Selections.MultipleMatchesException.class,
                () -> Selections.exactlyOne(server.windows().stream().toList()),
                "two windows is not one window");
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

        String rendered = expression.toString();

        assertTrue(rendered.contains("window_name"), "a lambda could not say this: " + rendered);
        assertTrue(rendered.contains("window_active"), rendered);
    }

    /**
     * A caller may build a field carrying a canonical field's name and answering a different
     * question, which is why such a field is never eligible to be lowered to tmux's own predicate.
     * These two disagree over the same real rows, so lowering this one by its name would change the
     * answer rather than only where it was computed.
     */
    @Test
    void aCallerBuiltFieldIsAnsweredHereRatherThanByTmux(Server server) {
        String running = server.panes().get(0).currentCommand();
        Fields.TextField<Pane> prefixed =
                Fields.text("pane_current_command", (Pane pane) -> "shell-" + pane.currentCommand());

        assertFalse(prefixed.ref().provenance().lowerable());
        assertEquals(
                1,
                server.panes().stream().filter(prefixed.is("shell-" + running)).count());
        assertEquals(
                0,
                server.panes().stream()
                        .filter(Pane_.command().is("shell-" + running))
                        .count(),
                "the canonical field of the same name answers differently over these rows");
    }
}
