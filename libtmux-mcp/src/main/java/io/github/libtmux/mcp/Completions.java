package io.github.libtmux.mcp;

import io.github.libtmux.Session;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Offers the ids that actually exist, as someone types one.
 *
 * <p>Without this, {@code completion/complete} either is not answered at all or answers from a
 * fixed list, and finding a pane id costs a round trip: list every pane, read the listing, pick one.
 * Answered live, the ids are simply there — and they are the ids that exist right now rather than
 * the ones a listing showed some turns ago.
 *
 * <p>The protocol allows completion only against a prompt argument or a resource template variable,
 * not against a tool argument, so those are what is wired here.
 */
final class Completions {

    /** The protocol caps a completion response at a hundred values, and a person cannot read that many. */
    private static final int MOST = 100;

    private Completions() {}

    static List<McpServerFeatures.SyncCompletionSpecification> all(Connection connection) {
        List<McpServerFeatures.SyncCompletionSpecification> specifications = new ArrayList<>();

        for (String prompt : List.of("run_and_wait", "watch_until_ready")) {
            specifications.add(completing(
                    new McpSchema.PromptReference(prompt), Prompts.PANE_ARGUMENT, () -> paneIds(connection)));
        }
        specifications.add(completing(
                new McpSchema.PromptReference("clean_up_safely"),
                Prompts.SESSION_ARGUMENT,
                () -> sessionNames(connection)));

        specifications.add(completing(
                new McpSchema.ResourceReference(Resources.PANE_TEMPLATE), "pane_id", () -> paneIds(connection)));
        specifications.add(completing(
                new McpSchema.ResourceReference(Resources.PANE_CONTENT_TEMPLATE),
                "pane_id",
                () -> paneIds(connection)));
        specifications.add(completing(
                new McpSchema.ResourceReference(Resources.SESSION_TEMPLATE),
                "session_name",
                () -> sessionNames(connection)));

        return List.copyOf(specifications);
    }

    private static List<String> paneIds(Connection connection) {
        return connection.server().panes().stream()
                .map(pane -> pane.id().value())
                .toList();
    }

    private static List<String> sessionNames(Connection connection) {
        return connection.server().sessions().stream().map(Session::name).toList();
    }

    /**
     * A tmux server that has gone answers no completion rather than failing the request: a client
     * asking what to type is not a place to report that the world ended.
     */
    private static McpServerFeatures.SyncCompletionSpecification completing(
            McpSchema.CompleteReference reference, String argument, Supplier<List<String>> values) {
        return new McpServerFeatures.SyncCompletionSpecification(reference, (exchange, request) -> {
            if (!argument.equals(request.argument().name())) {
                return empty();
            }
            String typed = request.argument().value();
            List<String> candidates;
            try {
                candidates = values.get();
            } catch (RuntimeException e) {
                return empty();
            }
            List<String> matching = candidates.stream()
                    .filter(candidate -> typed == null || typed.isEmpty() || candidate.startsWith(typed))
                    .limit(MOST)
                    .toList();
            return new McpSchema.CompleteResult(new McpSchema.CompleteResult.CompleteCompletion(
                    matching, candidates.size(), candidates.size() > matching.size()));
        });
    }

    private static McpSchema.CompleteResult empty() {
        return new McpSchema.CompleteResult(new McpSchema.CompleteResult.CompleteCompletion(List.of(), 0, false));
    }
}
