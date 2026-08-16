package io.github.libtmux.mcp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Pane;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.Function;

/**
 * The same state, addressable rather than asked for.
 *
 * <p>A tool is a verb a model has to choose. A resource is a noun a client can hold: it can attach
 * {@code tmux://panes/%1/content} to a conversation, refresh it, and show it to a person, none of
 * which spends a tool call or a model's decision. Declaring only tools gives that up.
 *
 * <p>The templated ones carry the id in the URI, so a client that has listed panes once can address
 * every one of them without asking this server how — and a client that supports completion can
 * offer the ids that exist as a person types one.
 */
final class Resources {

    private static final ObjectMapper JSON = Answers.mapper();

    private static final String JSON_MIME = "application/json";

    /**
     * Terminal text is not JSON and must not be parsed as it. It is what a program drew on a grid,
     * and a client that renders it as anything else will make a mess of a progress bar.
     */
    private static final String TEXT_MIME = "text/plain";

    static final String PANE_TEMPLATE = "tmux://panes/{pane_id}";

    static final String PANE_CONTENT_TEMPLATE = "tmux://panes/{pane_id}/content";

    static final String SESSION_TEMPLATE = "tmux://sessions/{session_name}";

    private Resources() {}

    static List<McpServerFeatures.SyncResourceSpecification> fixed(Connection connection) {
        return List.of(
                resource(
                        "tmux://server",
                        "This tmux server",
                        "Which server this connection acts on, how much is on it, and which pane this "
                                + "conversation is coming through.",
                        () -> Listings.whoami(connection.server(), connection.caller(), connection.ceiling())),
                resource(
                        "tmux://sessions",
                        "All sessions",
                        "Every session on this server, with the windows in each.",
                        () -> Listings.sessions(connection.server())),
                resource(
                        "tmux://panes",
                        "All panes",
                        "Every pane on this server, with the id other tools take as a target.",
                        () -> new Listings.Panes(
                                connection.server().panes().size(),
                                Listings.describe(connection.server().panes(), connection.caller()),
                                null)));
    }

    static List<McpServerFeatures.SyncResourceTemplateSpecification> templated(Connection connection) {
        return List.of(
                jsonTemplate(SESSION_TEMPLATE, "One session", "A session and the windows in it.", values -> {
                    var found = Targets.session(connection.server(), values.get(0));
                    return new Listings.Sessions(
                            1,
                            List.of(new Listings.SessionSummary(
                                    found.id().value(),
                                    found.name(),
                                    found.attached(),
                                    found.windows().size(),
                                    found.windows().stream()
                                            .map(window -> window.name())
                                            .toList())),
                            null);
                }),
                jsonTemplate(
                        PANE_TEMPLATE,
                        "One pane",
                        "What tmux knows about a pane: what is running in it, where, and how big it is.",
                        values -> Listings.describe(
                                        List.of(Targets.pane(connection.server(), values.get(0))), connection.caller())
                                .get(0)),
                template(
                        PANE_CONTENT_TEMPLATE,
                        "What a pane is showing",
                        "The text a pane currently shows, newest last. This is terminal output, not JSON.",
                        TEXT_MIME,
                        values -> {
                            Pane pane = Targets.pane(connection.server(), values.get(0));
                            return String.join("\n", Watching.withoutTrailingBlanks(pane.capture()));
                        }));
    }

    // ------------------------------------------------------------------ plumbing

    private static McpServerFeatures.SyncResourceSpecification resource(
            String uri, String title, String description, java.util.function.Supplier<Object> read) {
        McpSchema.Resource declared = McpSchema.Resource.builder(uri, title)
                .description(description)
                .mimeType(JSON_MIME)
                .build();
        return new McpServerFeatures.SyncResourceSpecification(
                declared,
                (exchange, request) -> McpSchema.ReadResourceResult.builder(
                                List.of(McpSchema.TextResourceContents.builder(request.uri(), render(read.get()))
                                        .mimeType(JSON_MIME)
                                        .build()))
                        .build());
    }

    private static McpServerFeatures.SyncResourceTemplateSpecification jsonTemplate(
            String pattern, String title, String description, Function<List<String>, Object> read) {
        return template(pattern, title, description, JSON_MIME, values -> render(read.apply(values)));
    }

    private static McpServerFeatures.SyncResourceTemplateSpecification template(
            String pattern, String title, String description, String mime, Function<List<String>, String> read) {
        McpSchema.ResourceTemplate declared = McpSchema.ResourceTemplate.builder(pattern, title)
                .description(description)
                .mimeType(mime)
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(
                declared,
                (exchange, request) -> McpSchema.ReadResourceResult.builder(List.of(
                                McpSchema.TextResourceContents.builder(
                                                request.uri(), read.apply(Uris.values(pattern, request.uri())))
                                        .mimeType(mime)
                                        .build()))
                        .build());
    }

    private static String render(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("could not render a resource", e);
        }
    }
}
