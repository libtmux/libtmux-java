package io.github.libtmux.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane_;
import io.github.libtmux.Window_;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TmuxFiltersTest {

    @Test
    void anExactCommandIsAnEqualityFormat() {
        assertEquals(
                Optional.of("#{==:#{pane_current_command},nvim}"),
                TmuxFilters.format(Pane_.command().is("nvim")));
    }

    @Test
    void aPrefixIsAGlobAndAFlagIsOneOrZero() {
        FilterExpr<io.github.libtmux.Pane> expression =
                Pane_.command().startsWith("nv").and(Pane_.active().isTrue());

        assertEquals(
                Optional.of("#{&&:#{m:nv*,#{pane_current_command}},#{==:#{pane_active},1}}"),
                TmuxFilters.format(expression));
    }

    @Test
    void aRelationStaysLocal() {
        assertEquals(
                Optional.empty(),
                TmuxFilters.format(Window_.panes().any(Pane_.command().is("nvim"))));
    }

    @Test
    void aCommaInTheOperandStaysLocal() {
        assertEquals(Optional.empty(), TmuxFilters.format(Pane_.command().is("a,b")));
    }

    @Test
    void aPlainRegexIsLowered() {
        assertEquals(
                Optional.of("#{m/r:^nv,#{pane_current_command}}"),
                TmuxFilters.format(Pane_.command().matches(Pattern.compile("^nv"))));
    }

    @Test
    void literalRejectsFormatSyntax() {
        assertTrue(TmuxFilters.literal("build"));
        assertTrue(!TmuxFilters.literal("a:b"));
    }
}
