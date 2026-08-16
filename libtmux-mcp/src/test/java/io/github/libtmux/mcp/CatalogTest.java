package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The promises the tool surface makes to a model, checked without tmux.
 *
 * <p>A description is the only documentation a model gets and a hint is what a client decides to
 * confirm with a person on. Both are easy to leave off a new tool and impossible to notice missing.
 */
final class CatalogTest {

    @Test
    void everyToolIsNamedForThisServerAndNamedOnlyOnce() {
        Set<String> seen = new HashSet<>();

        for (ToolSpec tool : Catalog.tools()) {
            assertTrue(tool.name().startsWith("tmux_"), tool.name() + " must say which server it belongs to");
            assertTrue(seen.add(tool.name()), tool.name() + " is declared twice");
        }
    }

    @Test
    void everyToolTellsAModelWhatItIsFor() {
        for (ToolSpec tool : Catalog.tools()) {
            assertFalse(tool.description().isBlank(), tool.name() + " has no description");
            assertTrue(
                    tool.description().length() > 40,
                    tool.name() + " describes itself in too few words for a model to choose it");
            assertFalse(tool.title().isBlank(), tool.name() + " has no title for a person to read");
        }
    }

    /** A client decides what to confirm with a person from these, so they may never be absent. */
    @Test
    void whatAToolCanDestroyIsDeclaredToTheClient() {
        for (ToolSpec tool : Catalog.tools()) {
            McpSchema.ToolAnnotations annotations = tool.describe().annotations();
            assertNotNull(annotations, tool.name() + " carries no annotations");
            assertEquals(
                    tool.safety() == Safety.READONLY,
                    annotations.readOnlyHint(),
                    tool.name() + " disagrees with its own safety about being read-only");
            assertEquals(
                    tool.safety() == Safety.DESTRUCTIVE,
                    annotations.destructiveHint(),
                    tool.name() + " disagrees with its own safety about being destructive");
        }
    }

    @Test
    void everyArgumentIsDescribedAndTheRequiredOnesAreListed() {
        for (ToolSpec tool : Catalog.tools()) {
            Map<String, Object> schema = Argument.objectSchema(tool.arguments());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) Objects.requireNonNull(schema.get("properties"), "properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) Objects.requireNonNull(schema.get("required"), "required");

            assertEquals(tool.arguments().size(), properties.size(), tool.name() + " lost an argument in its schema");
            for (Argument argument : tool.arguments()) {
                assertFalse(
                        argument.description().isBlank(),
                        tool.name() + " does not say what '" + argument.name() + "' is");
                assertEquals(
                        argument.required(),
                        required.contains(argument.name()),
                        tool.name() + " disagrees with itself about whether '" + argument.name() + "' is required");
            }
        }
    }

    /**
     * The point of the ceiling: a tool above it is never listed, so a model is not offered something
     * it will only be refused.
     */
    @Test
    void aCeilingRemovesToolsRatherThanRefusingThem() {
        Map<String, ToolSpec> readonly = Catalog.offered(Safety.READONLY);
        Map<String, ToolSpec> mutating = Catalog.offered(Safety.MUTATING);
        Map<String, ToolSpec> everything = Catalog.offered(Safety.DESTRUCTIVE);

        assertTrue(readonly.values().stream().allMatch(tool -> tool.safety() == Safety.READONLY));
        assertFalse(readonly.containsKey("tmux_run"), "running a command changes the pane");
        assertFalse(mutating.containsKey("tmux_kill"), "killing is not something a mutating server offers");
        assertTrue(everything.containsKey("tmux_kill"));
        assertTrue(readonly.size() < mutating.size() && mutating.size() < everything.size());
        assertEquals(everything.size(), Catalog.tools().size(), "the widest ceiling offers everything declared");
    }

    /** The tools a model needs before it can do anything else must survive the strictest ceiling. */
    @Test
    void findingOutWhatIsThereIsAlwaysOffered() {
        Map<String, ToolSpec> readonly = Catalog.offered(Safety.READONLY);

        assertTrue(readonly.containsKey("tmux_whoami"));
        assertTrue(readonly.containsKey("tmux_list_panes"));
        assertTrue(readonly.containsKey("tmux_capture_pane"));
        assertTrue(readonly.containsKey("tmux_wait_for_text"), "watching is reading, whatever it waits for");
    }
}
