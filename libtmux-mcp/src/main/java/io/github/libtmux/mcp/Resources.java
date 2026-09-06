package io.github.libtmux.mcp;

import com.fasterxml.jackson.core.JacksonException;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;

/** The single static disclosure resource exposed by this MCP server. */
final class Resources {

    static final String CAPABILITIES_URI = "tmux://capabilities";

    private Resources() {}

    static List<McpServerFeatures.SyncResourceSpecification> fixed(Connection connection) {
        String payload = render(connection.surface().capabilities(connection.server()));
        McpSchema.Resource declared = McpSchema.Resource.builder(CAPABILITIES_URI, "tmux capabilities")
                .description("The startup-frozen effective tool surface and selected tmux socket.")
                .mimeType("application/json")
                .build();
        return List.of(new McpServerFeatures.SyncResourceSpecification(
                declared,
                (exchange, request) -> McpSchema.ReadResourceResult.builder(
                                List.of(McpSchema.TextResourceContents.builder(request.uri(), payload)
                                        .mimeType("application/json")
                                        .build()))
                        .build()));
    }

    private static String render(Object value) {
        try {
            return Answers.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("could not render the capabilities resource", e);
        }
    }
}
