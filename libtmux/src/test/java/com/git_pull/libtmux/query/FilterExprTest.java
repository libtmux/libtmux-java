package com.git_pull.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.git_pull.libtmux.query.Model.Pane;
import com.git_pull.libtmux.query.Model.Pane_;
import com.git_pull.libtmux.query.Model.Window;
import com.git_pull.libtmux.query.Model.Window_;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The semantics an expression must have to be worth building instead of writing a lambda. */
final class FilterExprTest {

    private static final Pane NVIM = new Pane("%1", "nvim", 0, true);
    private static final Pane SHELL = new Pane("%2", "zsh", 1, false);
    private static final Pane WATCH = new Pane("%3", "nvtop", 2, false);

    private static final List<Pane> PANES = List.of(NVIM, SHELL, WATCH);

    @Test
    void anExpressionIsUsableAsAPredicate() {
        FilterExpr<Pane> editors = Pane_.command().startsWith("nv");

        assertEquals(
                List.of("%1", "%3"),
                PANES.stream().filter(editors).map(Pane::id).toList(),
                "stream().filter must accept the expression with no adapter");
    }

    @Test
    void booleanCompositionBehaves() {
        FilterExpr<Pane> editorAndActive =
                Pane_.command().startsWith("nv").and(Pane_.active().isTrue());
        FilterExpr<Pane> editorOrSecond =
                Pane_.command().is("zsh").or(Pane_.index().atLeast(2));

        assertEquals(List.of("%1"), matching(editorAndActive));
        assertEquals(List.of("%2", "%3"), matching(editorOrSecond));
        assertEquals(List.of("%2"), matching(Pane_.command().startsWith("nv").negate()));
    }

    @Test
    void emptyConjunctionIsTrueAndEmptyDisjunctionIsFalse() {
        assertTrue(FilterExpr.<Pane>and(List.of()).test(NVIM), "an empty conjunction is the identity");
        assertFalse(FilterExpr.<Pane>or(List.of()).test(NVIM), "an empty disjunction is the zero");
    }

    @Test
    void toManyQuantifiersIncludingVacuousAll() {
        Window busy = new Window("@1", "busy", true, PANES, Optional.of(NVIM));
        Window empty = new Window("@2", "empty", false, List.of(), Optional.empty());
        FilterExpr<Pane> editor = Pane_.command().startsWith("nv");

        assertTrue(Window_.panes().any(editor).test(busy));
        assertFalse(Window_.panes().all(editor).test(busy));
        assertFalse(Window_.panes().none(editor).test(busy));

        assertFalse(Window_.panes().any(editor).test(empty), "nothing satisfies any over an empty relation");
        assertTrue(Window_.panes().all(editor).test(empty), "everything satisfies all over an empty relation");
        assertTrue(Window_.panes().none(editor).test(empty));
    }

    @Test
    void toOneRequiresThePresentTargetToMatch() {
        Window busy = new Window("@1", "busy", true, PANES, Optional.of(NVIM));
        Window shellLed = new Window("@2", "shell", true, PANES, Optional.of(SHELL));
        Window headless = new Window("@3", "headless", false, PANES, Optional.empty());
        FilterExpr<Pane> editor = Pane_.command().startsWith("nv");

        assertTrue(Window_.activePane().is(editor).test(busy));
        assertFalse(Window_.activePane().is(editor).test(shellLed));
        assertFalse(Window_.activePane().is(editor).test(headless), "an absent target does not satisfy is");
    }

    /**
     * The boundary between an expression and a bare predicate, pinned so it cannot drift silently.
     */
    @Test
    void combiningWithALambdaYieldsAPlainPredicateNotAnExpression() {
        FilterExpr<Pane> expression = Pane_.command().startsWith("nv");

        // Two expressions combine into an expression, which is still inspectable.
        FilterExpr<Pane> combined = expression.and(Pane_.active().isTrue());
        assertEquals("(command starts-with nv and active == true)", combined.describe());

        // A lambda selects the inherited Predicate overload; the result is deliberately not one.
        java.util.function.Predicate<Pane> degraded = expression.and(pane -> pane.index() == 0);
        assertFalse(degraded instanceof FilterExpr, "a lambda cannot be described, so it is not an expression");
        assertTrue(degraded.test(NVIM));
    }

    @Test
    void expressionsRemainInspectable() {
        FilterExpr<Window> expression = Window_.name()
                .contains("dev")
                .and(Window_.panes().any(Pane_.command().is("nvim")));

        assertEquals(
                "(name contains dev and panes any (command == nvim))",
                expression.describe(),
                "a named expression must read back as what it filters on");
    }

    @Test
    void theTreeIsAnExhaustivelySwitchableValue() {
        FilterExpr<Pane> expression =
                Pane_.command().is("nvim").or(Pane_.index().greaterThan(1));

        assertEquals(2, countLeaves(expression), "the expression must be walkable as data");
    }

    /** An exhaustive switch: adding a node kind breaks this at compile time, which is the point. */
    private static <T> int countLeaves(FilterExpr<T> expression) {
        return switch (expression) {
            case FilterExpr.And<T> and ->
                and.operands().stream().mapToInt(FilterExprTest::countLeaves).sum();
            case FilterExpr.Or<T> or ->
                or.operands().stream().mapToInt(FilterExprTest::countLeaves).sum();
            case FilterExpr.Not<T> not -> countLeaves(not.operand());
            case FilterExpr.Compare<T, ?> compare -> 1;
            case FilterExpr.ToMany<T, ?> toMany -> countLeaves(toMany.predicate());
            case FilterExpr.ToOne<T, ?> toOne -> countLeaves(toOne.predicate());
        };
    }

    @Test
    void regexAndMembershipOperators() {
        assertEquals(List.of("%1", "%3"), matching(Pane_.command().matches(Pattern.compile("^nv"))));
        assertEquals(List.of("%1", "%2"), matching(Pane_.command().in(List.of("nvim", "zsh"))));
    }

    @Test
    void cardinalityDistinguishesNoneFromSeveral() {
        assertEquals(SHELL, Selections.exactlyOne(matchingPanes(Pane_.command().is("zsh"))));

        assertThrows(
                Selections.NoMatchException.class,
                () -> Selections.exactlyOne(matchingPanes(Pane_.command().is("emacs"))));
        assertThrows(
                Selections.MultipleMatchesException.class,
                () -> Selections.exactlyOne(matchingPanes(Pane_.command().startsWith("nv"))));

        assertEquals(
                Optional.empty(),
                Selections.oneOrEmpty(matchingPanes(Pane_.command().is("emacs"))));
        assertEquals(
                Optional.of(SHELL),
                Selections.oneOrEmpty(matchingPanes(Pane_.command().is("zsh"))));
        assertThrows(
                Selections.MultipleMatchesException.class,
                () -> Selections.oneOrEmpty(matchingPanes(Pane_.command().startsWith("nv"))),
                "at most one must still reject several rather than pick one");
    }

    private static List<String> matching(FilterExpr<Pane> expression) {
        return PANES.stream().filter(expression).map(Pane::id).toList();
    }

    private static List<Pane> matchingPanes(FilterExpr<Pane> expression) {
        return PANES.stream().filter(expression).toList();
    }
}
