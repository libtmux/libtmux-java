package io.github.libtmux.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** One authoritative structured-tool definition. */
record ToolSpec(
        String name,
        String title,
        String description,
        Toolset toolset,
        ProcessReach processReach,
        Set<TmuxEffect> effects,
        Set<OutputClass> outputClasses,
        boolean mayExposeSecrets,
        boolean mayReturnUntrustedContent,
        boolean amplifiesFutureInput,
        Annotations annotations,
        List<Argument> arguments,
        Map<String, Set<InputSink>> inputSinks,
        Map<String, String> inputLiteralization,
        Set<String> nestedAuthority,
        OutputSchema output,
        Function<Call, Object> answer) {

    static final String CAPABILITY_META_KEY = "com.git-pull.libtmux-mcp/capability";

    ToolSpec {
        name = requireText(name, "name");
        title = requireText(title, "title");
        description = requireText(description, "description");
        Objects.requireNonNull(toolset, "toolset");
        Objects.requireNonNull(processReach, "processReach");
        effects = immutableEnums(effects, "effects");
        outputClasses = immutableEnumSet(outputClasses, "outputClasses");
        Objects.requireNonNull(annotations, "annotations");
        arguments = List.copyOf(arguments);
        inputSinks = immutableSinks(inputSinks);
        inputLiteralization = Collections.unmodifiableMap(new LinkedHashMap<>(inputLiteralization));
        nestedAuthority = Collections.unmodifiableSet(new LinkedHashSet<>(nestedAuthority));
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(answer, "answer");
    }

    enum Toolset {
        INSPECT("inspect"),
        MANAGE("manage"),
        EXECUTE("execute"),
        TEARDOWN("teardown");

        private final String wireName;

        Toolset(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        static Toolset ofWireName(String name) {
            for (Toolset toolset : values()) {
                if (toolset.wireName.equals(name)) {
                    return toolset;
                }
            }
            throw new IllegalArgumentException(
                    "unknown toolset '" + name + "'; expected inspect, manage, execute or teardown");
        }
    }

    enum ProcessReach {
        NONE("none"),
        CONFIGURED_PROCESS("configured-process"),
        PANE_INPUT("pane-input"),
        PANE_COMMAND("pane-command"),
        HOST_COMMAND("host-command");

        private final String wireName;

        ProcessReach(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    enum TmuxEffect {
        OBSERVE("observe"),
        CHANGE("change"),
        DELETE("delete");

        private final String wireName;

        TmuxEffect(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    enum OutputClass {
        TMUX_METADATA("tmux-metadata"),
        TERMINAL_CONTENT("terminal-content"),
        PROCESS_ENVIRONMENT("process-environment"),
        CONFIGURED_COMMAND("configured-command");

        private final String wireName;

        OutputClass(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    enum InputSink {
        NONE("none"),
        TMUX_LOOKUP("tmux-lookup"),
        TMUX_STATE("tmux-state"),
        PANE_INPUT("pane-input"),
        SHELL_COMMAND("shell-command"),
        PROCESS_ARGV("process-argv"),
        REGEX("regex"),
        NESTED_TOOL("nested-tool"),
        TMUX_FORMAT("tmux-format");

        private final String wireName;

        InputSink(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    record Annotations(boolean readOnlyHint, boolean destructiveHint, boolean idempotentHint, boolean openWorldHint) {

        McpSchema.ToolAnnotations describe(String title) {
            return new McpSchema.ToolAnnotations(
                    title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint, null);
        }
    }

    static ToolSpec define(
            String name,
            String title,
            String details,
            Toolset toolset,
            ProcessReach processReach,
            Set<TmuxEffect> effects,
            Set<OutputClass> outputClasses,
            boolean mayExposeSecrets,
            boolean mayReturnUntrustedContent,
            Annotations annotations,
            List<Argument> arguments,
            Map<String, Set<InputSink>> inputSinks,
            Set<String> nestedAuthority,
            OutputSchema output,
            Function<Call, Object> answer) {
        String description = opener(name, toolset, processReach, outputClasses) + " " + details;
        return new ToolSpec(
                name,
                title,
                description,
                toolset,
                processReach,
                effects,
                outputClasses,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                false,
                annotations,
                arguments,
                inputSinks,
                Map.of(),
                nestedAuthority,
                output,
                answer);
    }

    /** The tool as a client sees it during discovery. */
    McpSchema.Tool describe() {
        return McpSchema.Tool.builder(name, inputSchema())
                .description(description)
                .annotations(annotations.describe(title))
                .meta(Map.of(CAPABILITY_META_KEY, capability()))
                .outputSchema(outputSchema())
                .build();
    }

    Map<String, Object> capability() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("title", title);
        row.put("description", description);
        row.put("toolset", toolset.wireName());
        row.put("processReach", processReach.wireName());
        row.put("tmuxEffects", wireNames(effects));
        row.put("outputClasses", wireNames(outputClasses));
        row.put("mayExposeSecrets", mayExposeSecrets);
        row.put("mayReturnUntrustedContent", mayReturnUntrustedContent);
        row.put("amplifiesFutureInput", amplifiesFutureInput);
        row.put(
                "annotations",
                Map.of(
                        "readOnlyHint", annotations.readOnlyHint(),
                        "destructiveHint", annotations.destructiveHint(),
                        "idempotentHint", annotations.idempotentHint(),
                        "openWorldHint", annotations.openWorldHint()));
        row.put("inputSchema", inputSchema());
        row.put("outputSchema", outputSchema());
        row.put("inputLiteralization", inputLiteralization);
        row.put("nestedAuthority", List.copyOf(nestedAuthority));
        return Collections.unmodifiableMap(row);
    }

    String controlledOpener() {
        return opener(name, toolset, processReach, outputClasses);
    }

    Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>(Argument.objectSchema(arguments));
        if (!name.equals("call_read_tools_batch")) {
            return Collections.unmodifiableMap(schema);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> originalProperties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> properties = new LinkedHashMap<>(originalProperties);
        Map<String, ToolSpec> catalog = new LinkedHashMap<>();
        for (ToolSpec tool : Catalog.tools()) {
            catalog.put(tool.name(), tool);
        }
        List<Map<String, Object>> alternatives = nestedAuthority.stream()
                .map(nested -> {
                    Map<String, Object> operationProperties = new LinkedHashMap<>();
                    operationProperties.put("tool", Map.of("type", "string", "const", nested));
                    ToolSpec selected = Objects.requireNonNull(catalog.get(nested), nested);
                    operationProperties.put("arguments", selected.inputSchema());
                    Map<String, Object> branch = new LinkedHashMap<>();
                    branch.put("type", "object");
                    branch.put("properties", operationProperties);
                    branch.put("required", List.of("tool"));
                    branch.put("additionalProperties", false);
                    return Collections.unmodifiableMap(branch);
                })
                .toList();
        Map<String, Object> items = alternatives.isEmpty() ? Map.of("not", Map.of()) : Map.of("oneOf", alternatives);
        properties.put(
                "operations",
                Map.of(
                        "type",
                        "array",
                        "items",
                        items,
                        "minItems",
                        1,
                        "maxItems",
                        16,
                        "description",
                        "Typed calls within the disclosed nested authority."));
        @SuppressWarnings("unchecked")
        Map<String, Object> onError = new LinkedHashMap<>((Map<String, Object>) properties.get("onError"));
        onError.put("enum", List.of("stop", "continue"));
        properties.put("onError", Collections.unmodifiableMap(onError));
        schema.put("properties", Collections.unmodifiableMap(properties));
        return Collections.unmodifiableMap(schema);
    }

    void validateArguments(Map<String, Object> values) {
        Argument.validate(arguments, values);
    }

    Map<String, Object> outputSchema() {
        return output.wireSchema();
    }

    void validateOutput(Object value) {
        output.validate(name, value);
    }

    private static String opener(
            String name, Toolset toolset, ProcessReach processReach, Set<OutputClass> outputClasses) {
        return switch (toolset) {
            case INSPECT -> inspectOpener(outputClasses);
            case MANAGE -> "Change tmux state; no client-supplied executable input.";
            case EXECUTE ->
                switch (processReach) {
                    case CONFIGURED_PROCESS -> "Start a pane's configured process; accepts no command payload.";
                    case PANE_INPUT ->
                        "Send input to a pane's program; a shell that receives it runs it with your user's permissions.";
                    case PANE_COMMAND -> "Run a shell command in a pane with your user's permissions.";
                    case NONE -> "Change tmux state; no client-supplied executable input.";
                    case HOST_COMMAND ->
                        throw new IllegalStateException(
                                name + " has no controlled opener for " + processReach.wireName());
                };
            case TEARDOWN -> "Delete tmux state; accepts no command payload.";
        };
    }

    private static String inspectOpener(Set<OutputClass> outputClasses) {
        if (outputClasses.contains(OutputClass.TERMINAL_CONTENT)) {
            return "Read pane output; accepts no client-supplied executable input. "
                    + "Returned content may be sensitive or untrusted.";
        }
        if (outputClasses.contains(OutputClass.PROCESS_ENVIRONMENT)) {
            return "Read the tmux environment; accepts no client-supplied executable input. "
                    + "Returned values may contain secrets.";
        }
        if (outputClasses.contains(OutputClass.CONFIGURED_COMMAND)) {
            return "Read configured tmux commands; accepts no client-supplied executable input. "
                    + "Returned values may contain executable configuration.";
        }
        return "Inspect tmux metadata; accepts no client-supplied executable input.";
    }

    ToolSpec withInputLiteralization(Map<String, String> literalization) {
        Map<String, Set<InputSink>> classifiedSinks = new LinkedHashMap<>(inputSinks);
        literalization.forEach((input, strategy) -> {
            Set<InputSink> current = Objects.requireNonNull(classifiedSinks.get(input), input);
            Set<InputSink> expanded = EnumSet.copyOf(current);
            expanded.add(
                    switch (strategy) {
                        case "double-hash-once" -> InputSink.TMUX_STATE;
                        case "validated-variable-name" -> InputSink.TMUX_LOOKUP;
                        default ->
                            throw new IllegalArgumentException("unknown input literalization '" + strategy + "'");
                    });
            classifiedSinks.put(input, expanded);
        });
        return new ToolSpec(
                name,
                title,
                description,
                toolset,
                processReach,
                effects,
                outputClasses,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                amplifiesFutureInput,
                annotations,
                arguments,
                classifiedSinks,
                literalization,
                nestedAuthority,
                output,
                answer);
    }

    ToolSpec amplifyingFutureInput() {
        return new ToolSpec(
                name,
                title,
                description,
                toolset,
                processReach,
                effects,
                outputClasses,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                true,
                annotations,
                arguments,
                inputSinks,
                inputLiteralization,
                nestedAuthority,
                output,
                answer);
    }

    ToolSpec withNestedAuthority(Set<String> effectiveNestedAuthority, Map<String, ToolSpec> catalog) {
        Set<TmuxEffect> aggregateEffects = EnumSet.of(TmuxEffect.OBSERVE);
        Set<OutputClass> aggregateOutputs = EnumSet.noneOf(OutputClass.class);
        boolean aggregateSecrets = false;
        boolean aggregateUntrusted = false;
        for (String nested : effectiveNestedAuthority) {
            ToolSpec selected = Objects.requireNonNull(catalog.get(nested), nested);
            aggregateEffects.addAll(selected.effects());
            aggregateOutputs.addAll(selected.outputClasses());
            aggregateSecrets |= selected.mayExposeSecrets();
            aggregateUntrusted |= selected.mayReturnUntrustedContent();
        }
        String body = description.substring(controlledOpener().length()).stripLeading();
        String aggregateDescription = opener(name, toolset, processReach, aggregateOutputs) + " " + body;
        return new ToolSpec(
                name,
                title,
                aggregateDescription,
                toolset,
                processReach,
                aggregateEffects,
                aggregateOutputs,
                aggregateSecrets,
                aggregateUntrusted,
                amplifiesFutureInput,
                annotations,
                arguments,
                inputSinks,
                inputLiteralization,
                effectiveNestedAuthority,
                output,
                answer);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return value;
    }

    private static <E extends Enum<E>> Set<E> immutableEnums(Set<E> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " is empty");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values, String field) {
        Objects.requireNonNull(values, field);
        if (values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    private static Map<String, Set<InputSink>> immutableSinks(Map<String, Set<InputSink>> declared) {
        Objects.requireNonNull(declared, "inputSinks");
        Map<String, Set<InputSink>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Set<InputSink>> entry : declared.entrySet()) {
            String input = requireText(entry.getKey(), "input sink key");
            Set<InputSink> sinks = immutableEnums(entry.getValue(), input + " sinks");
            if (copied.put(input, sinks) != null) {
                throw new IllegalArgumentException("duplicate input sink '" + input + "'");
            }
        }
        return Collections.unmodifiableMap(copied);
    }

    private static <E extends Enum<E>> List<String> wireNames(Set<E> values) {
        return values.stream()
                .map(value -> {
                    if (value instanceof TmuxEffect effect) {
                        return effect.wireName();
                    }
                    if (value instanceof OutputClass output) {
                        return output.wireName();
                    }
                    if (value instanceof InputSink sink) {
                        return sink.wireName();
                    }
                    throw new IllegalArgumentException("unsupported capability enum " + value);
                })
                .toList();
    }
}
