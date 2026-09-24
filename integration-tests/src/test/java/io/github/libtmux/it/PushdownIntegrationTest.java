package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane_;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Session_;
import io.github.libtmux.Window;
import io.github.libtmux.WindowSpec;
import io.github.libtmux.Window_;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.query.FilterExpr;
import io.github.libtmux.query.TmuxFilters;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * A filter tmux applies must select exactly the rows the same expression selects locally.
 *
 * <p>Every expression is checked twice: through the filtered read, which sends what {@link
 * TmuxFilters} lowers as {@code -f}, and against a full capture filtered in Java. A lowering that
 * disagrees with Java drops rows silently, because the local filter runs only on what tmux kept.
 */
@ExtendWith(TmuxExtension.class)
final class PushdownIntegrationTest {

    private static final List<String> NAMES =
            List.of("123", "ddd", "trail\\", "star*name", "a b", "café", "dot.ted", "[br]", "Upper", "q?", "pay$day");

    @Test
    void aPushedFilterSelectsWhatTheLocalFilterSelects(Server server) {
        Session first = server.sessions().get(0);
        List<String> accepted = new java.util.ArrayList<>();
        for (String name : NAMES) {
            try {
                server.newSession(name);
            } catch (LibTmuxException refused) {
                // 3.7 rejects . and : in a name; earlier releases store them as _.
                assertTrue(String.valueOf(refused.getMessage()).contains("invalid session name"), refused::toString);
                continue;
            }
            accepted.add(name);
            first.newWindow(WindowSpec.builder().named(name).build());
        }
        // Windows 0 through 10 now exist, so 9 and 10 straddle a decimal digit boundary. A filtered
        // read keeps whole sessions, so a session whose only window is 9 is what tmux must not drop.
        Session nine = server.newSession("nine");
        Window initial = nine.windows().get(0);
        nine.newWindow(WindowSpec.builder().named("nine").atIndex(9).build());
        initial.kill();

        Stream<Executable> sessions = Stream.of(
                        Session_.name().is("123"),
                        Session_.name().is("trail\\"),
                        Session_.name().isNot("ddd"),
                        Session_.name().contains("*"),
                        Session_.name().contains("\\"),
                        Session_.name().startsWith("tra"),
                        Session_.name().startsWith("[br"),
                        Session_.name().endsWith("?"),
                        Session_.name().endsWith("é"),
                        Session_.name().contains("upper"),
                        Session_.name().in(List.of("a b", "dot.ted", "nope")),
                        Session_.name().matches(Pattern.compile("\\d+")),
                        Session_.attached().isFalse(),
                        Session_.name().startsWith("d").negate(),
                        Session_.name().is("123").or(Session_.name().is("ddd")))
                .map(expression -> agree(expression, server::sessions, server.sessions(), Session::name));
        Stream<Executable> windows = Stream.of(
                        Window_.index().lessThan(10),
                        Window_.index().atMost(9),
                        Window_.index().greaterThan(9),
                        Window_.index().atLeast(10),
                        Window_.index().is(10),
                        Window_.index().isNot(9),
                        Window_.name().is("q?"),
                        Window_.name().contains("."),
                        // A window or pane format is lifted into a loop over its session, one
                        // expansion deeper, so the glob escapes are checked there too.
                        Window_.name().endsWith("?"),
                        Window_.name().contains("*"),
                        Window_.name().contains("\\"),
                        Window_.name().startsWith("[br"),
                        Window_.index().atLeast(1).and(Window_.name().startsWith("t")))
                .map(expression -> agree(
                        expression,
                        server::windows,
                        server.windows(),
                        window -> window.id().value()));
        Stream<Executable> panes = Stream.of(
                        Pane_.index().is(0),
                        Pane_.active().isTrue(),
                        Pane_.width().greaterThan(9),
                        Pane_.command().isNot("cat"),
                        Pane_.command().startsWith("ca"),
                        Pane_.index().is(0).and(Pane_.command().contains("a")))
                .map(expression -> agree(
                        expression,
                        server::panes,
                        server.panes(),
                        pane -> pane.id().value()));

        // tmux doubles a backslash in the name it keeps, so a lookup by the given name must still
        // find it.
        Stream<Executable> lookups = accepted.stream()
                .map(name -> () -> assertTrue(
                        server.session(name).isPresent() && server.hasSession(name), "session(" + name + ")"));

        assertAll(Stream.of(sessions, windows, panes, lookups).flatMap(Function.identity()));
    }

    private static <T> Executable agree(
            FilterExpr<T> expression,
            Function<FilterExpr<T>, List<? extends T>> filtered,
            List<? extends T> all,
            Function<T, String> key) {
        return () -> assertEquals(
                all.stream().filter(expression).map(key).sorted().toList(),
                filtered.apply(expression).stream().map(key).sorted().toList(),
                expression + " lowered as " + TmuxFilters.format(expression));
    }
}
