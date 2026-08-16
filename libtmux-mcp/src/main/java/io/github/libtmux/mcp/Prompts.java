package io.github.libtmux.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Worked recipes, offered rather than remembered.
 *
 * <p>A tool description can say what one tool does. It cannot teach the shape of a job that takes
 * three of them in an order that matters — and that shape is exactly what an agent gets wrong
 * expensively, by polling, or by waiting on a run that has already failed.
 *
 * <p>Kept few and deliberate. A prompt list long enough to need reading is one nobody reads.
 */
final class Prompts {

    static final String PANE_ARGUMENT = "pane_id";

    static final String SESSION_ARGUMENT = "session_name";

    private Prompts() {}

    static List<McpServerFeatures.SyncPromptSpecification> all() {
        return List.of(
                prompt(
                        "run_and_wait",
                        "Run a command and wait for it",
                        List.of(
                                argument(PANE_ARGUMENT, "The pane to run it in, such as %1.", true),
                                argument("command", "The shell command to run.", true)),
                        values -> """
                                Run this in tmux pane %s and wait for it:

                                    tmux_run(pane_id="%s", command=%s, timeout=60)

                                Read `exit_status` and `outcome` from the result. `outcome` is SIGNALLED when \
                                the command finished, TIMED_OUT when it was still running at the deadline, and \
                                SERVER_GONE when tmux itself died — those mean different things and only the \
                                first makes `exit_status` meaningful.

                                Do not send the command with tmux_send_keys and then poll tmux_capture_pane to \
                                see whether it is done. That costs a call per look, and a quiet pane looks the \
                                same whether the command finished or hung.
                                """.formatted(
                                        value(values, PANE_ARGUMENT, "%1"),
                                        value(values, PANE_ARGUMENT, "%1"),
                                        quoted(value(values, "command", "true")))),
                prompt(
                        "watch_until_ready",
                        "Wait for something you did not start",
                        List.of(
                                argument(PANE_ARGUMENT, "The pane to watch, such as %1.", true),
                                argument("ready_text", "The text that means it is up.", true),
                                argument("failure_text", "The text that means it has failed.", false)),
                        values -> """
                                Wait for the process already running in tmux pane %s:

                                    tmux_wait_for_text(pane_id="%s", patterns=[%s], stop=[%s], timeout=60)

                                Pass `stop` whenever a failure marker exists. Without it, a run that fails in \
                                five seconds is still waited on until the deadline, and what comes back is a \
                                timeout rather than the error.

                                Only output arriving after the call counts, so text already on screen will not \
                                satisfy it. If it times out and the thing is simply slow, call again with the \
                                `cursor` from the result rather than starting over.
                                """.formatted(
                                        value(values, PANE_ARGUMENT, "%1"),
                                        value(values, PANE_ARGUMENT, "%1"),
                                        quoted(value(values, "ready_text", "ready")),
                                        quoted(value(values, "failure_text", "error")))),
                prompt(
                        "find_the_pane",
                        "Find which pane something is in",
                        List.of(argument("looking_for", "What you are trying to find, in words.", true)),
                        values -> """
                                Find the pane for: %s

                                Choose by what you are matching on:
                                - What a pane is RUNNING, or where it is: tmux_list_panes, narrowed with a \
                                filter document. This reads tmux's own metadata and is one call.
                                - What a pane is SHOWING on screen: tmux_search_panes with the text. Listing \
                                tools cannot see pane contents.

                                Then act by the `id` it returns — never by position. Indexes move as panes come \
                                and go, so a position read a few turns ago can name a different pane now.
                                """.formatted(value(values, "looking_for", "the pane you need"))),
                prompt(
                        "build_workspace",
                        "Build a session from a description",
                        List.of(argument("what_for", "What the workspace is for.", true)),
                        values -> """
                                Build a tmux session for: %s

                                Send it as one document to tmux_apply_workspace rather than creating windows \
                                and panes one call at a time. One call cannot half-succeed, and a layout tmux \
                                would refuse is refused before anything exists.

                                %s
                                The commands in it are started, not waited for. To check one came up, watch its \
                                pane with tmux_wait_for_text using the ids the call returns.
                                """.formatted(value(values, "what_for", "the work at hand"), Workspaces.example())),
                prompt(
                        "clean_up_safely",
                        "End things without ending this conversation",
                        List.of(argument(SESSION_ARGUMENT, "The session to tidy up.", false)),
                        values -> """
                                Tidy up %s.

                                Call tmux_whoami first. When this MCP server was launched from inside tmux, one \
                                pane is the one this conversation travels through, and killing it — or the \
                                window or session holding it — ends your ability to act at all. tmux_whoami \
                                names it, and tmux_kill refuses it unless `confirm_self` is set.

                                Check tmux_list_clients too: an attached client means a person is watching, and \
                                what looks abandoned may be someone's screen.
                                """.formatted(value(values, SESSION_ARGUMENT, "the sessions no longer needed"))));
    }

    /**
     * A value a client sent, or something readable in its place.
     *
     * <p>An argument declared required is not one that arrives: a client may render a prompt before
     * anyone has filled it in. A recipe with a placeholder in it still teaches the shape; one that
     * failed to render teaches nothing.
     */
    private static String value(Map<String, String> values, String name, String whenMissing) {
        String given = values.get(name);
        return given == null || given.isEmpty() ? whenMissing : given;
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static McpSchema.PromptArgument argument(String name, String description, boolean required) {
        return new McpSchema.PromptArgument(name, name, description, required);
    }

    private static McpServerFeatures.SyncPromptSpecification prompt(
            String name,
            String title,
            List<McpSchema.PromptArgument> arguments,
            Function<Map<String, String>, String> body) {
        McpSchema.Prompt declared = McpSchema.Prompt.builder(name)
                .title(title)
                .description(title)
                .arguments(arguments)
                .build();
        return new McpServerFeatures.SyncPromptSpecification(declared, (exchange, request) -> {
            Map<String, String> values = strings(request.arguments());
            return McpSchema.GetPromptResult.builder(List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder(body.apply(values)).build())))
                    .description(title)
                    .build();
        });
    }

    private static Map<String, String> strings(Map<String, Object> arguments) {
        if (arguments == null) {
            return Map.of();
        }
        return arguments.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().toString(), (first, second) -> second));
    }
}
