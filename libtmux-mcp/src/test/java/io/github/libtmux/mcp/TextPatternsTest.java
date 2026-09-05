package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class TextPatternsTest {

    @Test
    void patternInputsAndSearchWorkAreFixedBeforeMatching() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TextPatterns.compile(List.of("x".repeat(TextPatterns.MAX_PATTERN_BYTES + 1)), false));

        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index <= TextPatterns.MAX_PATTERNS; index++) {
            tooMany.add("p" + index);
        }
        assertThrows(IllegalArgumentException.class, () -> TextPatterns.compile(tooMany, false));

        assertThrows(
                IllegalArgumentException.class,
                () -> TextPatterns.compile(java.util.Collections.nCopies(TextPatterns.MAX_PATTERNS + 1, ""), false));
        assertTrue(TextPatterns.compileOne("", false).matches("anything"));

        assertThrows(IllegalArgumentException.class, () -> TextPatterns.compile(List.of("(a+)\\1"), true));

        TextPatterns.WorkBudget budget = TextPatterns.searchBudget();
        for (int index = 0; index < TextPatterns.MAX_SEARCH_PANES; index++) {
            assertTrue(budget.tryStartPane());
        }
        assertFalse(budget.tryStartPane());
        assertTrue(budget.trySpend("x".repeat(TextPatterns.MAX_SEARCH_BYTES)));
        assertFalse(budget.trySpend("x"));
        TextPatterns.WorkBudget lines = TextPatterns.searchBudget();
        for (int index = 0; index < TextPatterns.MAX_SEARCH_LINES; index++) {
            assertTrue(lines.trySpend(""));
        }
        assertFalse(lines.trySpend(""));
        assertTrue(TextPatterns.MAX_SEARCH_TIME.toSeconds() > 0);
        assertTrue(TextPatterns.MAX_SEARCH_TIME.toSeconds() <= 5);

        assertTrue(input("search_panes", "pattern").containsKey("maxLength"));
        assertTrue(input("wait_for_text", "patterns").containsKey("maxItems"));
        assertTrue(input("wait_for_text", "patterns").toString().contains("maxLength"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(String tool, String name) {
        Map<String, Object> properties = (Map<String, Object>)
                Objects.requireNonNull(Catalog.named(tool).inputSchema().get("properties"), "properties");
        return (Map<String, Object>) Objects.requireNonNull(properties.get(name), name);
    }
}
