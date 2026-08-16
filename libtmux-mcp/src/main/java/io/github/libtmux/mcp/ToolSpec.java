package io.github.libtmux.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.Function;

/**
 * One tool: what a model is told about it, what it may do, and what it does.
 *
 * <p>Declared in one place so the three never drift apart. A tool whose {@link Safety} says it
 * destroys something also carries the annotation that says so, without anyone remembering to add
 * it.
 *
 * @param name the wire name, prefixed {@code tmux_} so it reads unambiguously beside other servers'
 * @param title what a client shows a person
 * @param description what a model reads to decide whether this is the tool it wants
 * @param safety how much damage it can do
 * @param arguments what it takes
 * @param answer what it does, given the arguments a model sent
 */
record ToolSpec(
        String name,
        String title,
        String description,
        Safety safety,
        List<Argument> arguments,
        Function<Call, Object> answer) {

    static ToolSpec of(
            String name,
            String title,
            String description,
            Safety safety,
            List<Argument> arguments,
            Function<Call, Object> answer) {
        return new ToolSpec(name, title, description, safety, List.copyOf(arguments), answer);
    }

    /**
     * The tool as the protocol describes it.
     *
     * <p>The hints are derived from {@link Safety} rather than stated per tool. A client uses them to
     * decide what to confirm with a person, so a tool that kills a session must never be able to
     * describe itself as read-only by omission.
     */
    McpSchema.Tool describe() {
        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                title,
                safety == Safety.READONLY,
                safety == Safety.DESTRUCTIVE,
                safety == Safety.READONLY,
                // tmux is a world this server does not own: another client may change it between two
                // calls, and a pane's contents come from programs nobody here started.
                true,
                null);
        return McpSchema.Tool.builder(name, Argument.objectSchema(arguments))
                .description(description)
                .annotations(annotations)
                .build();
    }
}
