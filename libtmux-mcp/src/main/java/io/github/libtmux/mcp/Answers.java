package io.github.libtmux.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;

/**
 * Turns what a tool worked out into what the protocol carries.
 *
 * <p>Every answer goes out twice: as {@code structuredContent} for a client that parses, and as the
 * same JSON in text for one that does not. That duplication is the protocol's own recommendation,
 * and it is why every tool answers with a record rather than a list — {@code structuredContent} is
 * an object, and an object with named fields is also what stops a model counting positions in an
 * array to find out how many panes it got.
 */
final class Answers {

    static final ObjectMapper JSON = mapper();

    private Answers() {}

    /**
     * How every answer is written.
     *
     * <p>Snake case, because that is what the arguments use. A model that sends {@code pane_id} and
     * reads back {@code paneId} is being asked to hold two conventions for one call.
     *
     * <p>A null field is left out entirely. Most answers have nothing to add to their {@code note},
     * and {@code "note":null} on every one of them is context spent to say nothing.
     */
    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }

    /** An answer a model can read and a client can parse. */
    static McpSchema.CallToolResult ok(Object value) {
        Map<String, Object> structured = asObject(value);
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(null, render(structured), null)))
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    /**
     * A failure a model can act on.
     *
     * <p>Reported as a tool error rather than thrown: a transport-level exception never reaches the
     * model, so the one participant able to choose a different pane never hears which one was wrong.
     */
    static McpSchema.CallToolResult failure(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(null, message, null)))
                .isError(true)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (value instanceof Map<?, ?> already) {
            return (Map<String, Object>) already;
        }
        return JSON.convertValue(value, Map.class);
    }

    private static String render(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("could not render a tool result", e);
        }
    }
}
