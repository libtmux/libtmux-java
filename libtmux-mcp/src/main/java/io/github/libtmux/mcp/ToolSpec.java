package io.github.libtmux.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.function.Function;

/**
 * One tool: what a model is told about it, what an operator offers, and what it does.
 *
 * <p>The operator's {@link Safety} ceiling and the protocol's {@link Effect} hint are separate: a
 * tool may remain useful at a mutating ceiling while still warning a client that its update can be
 * destructive.
 *
 * @param name the wire name, prefixed {@code tmux_} so it reads unambiguously beside other servers'
 * @param title what a client shows a person
 * @param description what a model reads to decide whether this is the tool it wants
 * @param safety the narrowest operator ceiling that offers it
 * @param effect how it may update its environment
 * @param arguments what it takes
 * @param answer what it does, given the arguments a model sent
 */
record ToolSpec(
        String name,
        String title,
        String description,
        Safety safety,
        Effect effect,
        List<Argument> arguments,
        Function<Call, Object> answer) {

    enum Effect {
        READ_ONLY,
        ADDITIVE,
        DESTRUCTIVE
    }

    static ToolSpec of(
            String name,
            String title,
            String description,
            Safety safety,
            Effect effect,
            List<Argument> arguments,
            Function<Call, Object> answer) {
        return new ToolSpec(name, title, description, safety, effect, List.copyOf(arguments), answer);
    }

    /**
     * The tool as the protocol describes it.
     *
     * <p>A client uses these hints to decide what to confirm with a person. {@link Safety} cannot
     * supply them: it controls availability, while {@link Effect} describes the updates a call may
     * make.
     */
    McpSchema.Tool describe() {
        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                title,
                effect == Effect.READ_ONLY,
                effect == Effect.DESTRUCTIVE,
                effect == Effect.READ_ONLY,
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
