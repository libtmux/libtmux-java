package io.github.libtmux.mcp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.Function;

/**
 * The same state, addressable rather than asked for.
 *
 * <p>A tool is a verb a model has to choose. A resource is a noun a client can hold: it can attach
 * {@code tmux://panes/%251/content} to a conversation, refresh it, and show it to a person, none of
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

    static final String SERVER_URI = "tmux://server";

    static final String SESSIONS_URI = "tmux://sessions";

    static final String PANES_URI = "tmux://panes";

    static final String PANE_TEMPLATE = "tmux://panes/{pane_id}";

    static final String PANE_CONTENT_TEMPLATE = "tmux://panes/{pane_id}/content";

    static final String SESSION_TEMPLATE = "tmux://sessions/{session_name}";

    private Resources() {}

    static String sessionUri(String name) {
        return SESSIONS_URI + "/" + Uris.segment(name);
    }

    static String paneUri(PaneId pane) {
        return PANES_URI + "/" + Uris.segment(pane.value());
    }

    static String paneContentUri(PaneId pane) {
        return paneUri(pane) + "/content";
    }

    static List<McpServerFeatures.SyncResourceSpecification> fixed(Connection connection) {
        return List.of(
                resource(
                        SERVER_URI,
                        "This tmux server",
                        "Which server this connection acts on, how much is on it, and which pane this "
                                + "conversation is coming through.",
                        () -> Listings.whoami(connection.server(), connection.caller(), connection.ceiling())),
                resource(
                        SESSIONS_URI,
                        "All sessions",
                        "Every session on this server, with the windows in each.",
                        () -> Listings.sessions(connection)),
                resource(
                        PANES_URI,
                        "All panes",
                        "Every pane on this server, with the id other tools take as a target.",
                        () -> {
                            List<Pane> panes = connection.server().panes();
                            return new Listings.Panes(
                                    panes.size(), Listings.describe(panes, connection.caller()), null);
                        }));
    }

    static List<McpServerFeatures.SyncResourceTemplateSpecification> templated(Connection connection) {
        return List.of(
                jsonTemplate(SESSION_TEMPLATE, "One session", "A session and the windows in it.", values -> {
                    var found = Listings.session(connection, values.get(0));
                    return new Listings.Sessions(1, List.of(found), null);
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
                            return String.join("\n", Screen.withoutTrailingBlanks(pane.capture()));
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
