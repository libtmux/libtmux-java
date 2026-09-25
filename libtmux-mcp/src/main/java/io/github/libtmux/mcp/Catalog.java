package io.github.libtmux.mcp;

import static io.github.libtmux.mcp.OutputSchema.ValueType.BOOLEAN;
import static io.github.libtmux.mcp.OutputSchema.ValueType.INTEGER;
import static io.github.libtmux.mcp.OutputSchema.ValueType.STRING;
import static io.github.libtmux.mcp.ToolSpec.InputSink.SHELL_COMMAND;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_FORMAT;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_LOOKUP;
import static io.github.libtmux.mcp.ToolSpec.InputSink.TMUX_STATE;
import static io.github.libtmux.mcp.ToolSpec.ProcessReach.NONE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.DELETE;
import static io.github.libtmux.mcp.ToolSpec.TmuxEffect.OBSERVE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.EXECUTE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.INSPECT;
import static io.github.libtmux.mcp.ToolSpec.Toolset.MANAGE;
import static io.github.libtmux.mcp.ToolSpec.Toolset.TEARDOWN;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Every public structured tool, declared once in deterministic registration order. */
final class Catalog {

    static final OutputSchema SESSION_OUTPUT =
            shape(field("id", STRING), field("name", STRING), field("attached", BOOLEAN), field("windows", INTEGER));
    static final OutputSchema WINDOW_OUTPUT = shape(
            field("id", STRING),
            field("index", INTEGER),
            field("name", STRING),
            field("session_id", STRING),
            field("active", BOOLEAN),
            field("panes", INTEGER),
            field("size", STRING));
    static final OutputSchema PANE_OUTPUT = shape(
            field("id", STRING),
            field("index", INTEGER),
            field("window_id", STRING),
            field("session_id", STRING),
            field("active", BOOLEAN),
            field("command", STRING),
            field("path", STRING),
            field("title", STRING),
            field("size", STRING));

    private static final List<ToolSpec> TOOLS = build();

    private Catalog() {}

    static List<ToolSpec> tools() {
        return TOOLS;
    }

    static ToolSpec named(String name) {
        return TOOLS.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown catalog tool '" + name + "'"));
    }

    static void validate(List<ToolSpec> tools) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, ToolSpec> byName = new LinkedHashMap<>();
        for (ToolSpec tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("duplicate tool '" + tool.name() + "'");
            }
            byName.put(tool.name(), tool);
        }
        for (ToolSpec tool : tools) {
            validateSchema(tool);
            validateReach(tool);
            if (tool.outputClasses().isEmpty()) {
                throw new IllegalArgumentException(tool.name() + " has no output class");
            }
            if (!tool.description().endsWith(" " + tool.controlledOpener())) {
                throw new IllegalArgumentException(tool.name() + " does not end with its controlled opener");
            }
            if (tool.processReach() == ToolSpec.ProcessReach.HOST_COMMAND) {
                throw new IllegalArgumentException(tool.name() + " exposes prohibited host-command reach");
            }
            Set<String> formatInputs = tool.inputSinks().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(TMUX_FORMAT))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (!formatInputs.equals(tool.inputLiteralization().keySet())
                    || tool.inputLiteralization().values().stream()
                            .anyMatch(strategy -> !Set.of("double-hash-once", "validated-variable-name")
                                    .contains(strategy))) {
                throw new IllegalArgumentException(tool.name() + " has inconsistent tmux-format controls");
            }
            for (Map.Entry<String, String> control : tool.inputLiteralization().entrySet()) {
                ToolSpec.InputSink classified =
                        control.getValue().equals("double-hash-once") ? TMUX_STATE : TMUX_LOOKUP;
                if (!Objects.requireNonNull(tool.inputSinks().get(control.getKey()), control.getKey())
                        .contains(classified)) {
                    throw new IllegalArgumentException(
                            tool.name() + " understates the sink for '" + control.getKey() + "'");
                }
            }
            if (tool.amplifiesFutureInput() != tool.name().equals("set_synchronize_panes")) {
                throw new IllegalArgumentException(tool.name() + " has incorrect future-input amplification");
            }
            if (!tool.annotations().equals(conservativeAnnotations())) {
                throw new IllegalArgumentException(
                        tool.name() + " is not conservative under unknown configuration provenance");
            }
            for (String nested : tool.nestedAuthority()) {
                if (nested.equals(tool.name()) || !names.contains(nested)) {
                    throw new IllegalArgumentException(tool.name() + " has invalid nested authority '" + nested + "'");
                }
            }
            if (!tool.nestedAuthority().isEmpty()) {
                ToolSpec derived = tool.withNestedAuthority(tool.nestedAuthority(), byName);
                if (!tool.effects().equals(derived.effects())
                        || !tool.outputClasses().equals(derived.outputClasses())
                        || tool.mayExposeSecrets() != derived.mayExposeSecrets()
                        || tool.mayReturnUntrustedContent() != derived.mayReturnUntrustedContent()) {
                    throw new IllegalArgumentException(tool.name() + " understates its nested capability union");
                }
            }
        }
    }

    private static List<ToolSpec> build() {
        List<ToolSpec> tools = new ArrayList<>();
        InspectTools.inspect(tools);
        ManageTools.manage(tools);
        ExecuteTools.execute(tools);
        TeardownTools.teardown(tools);
        List<ToolSpec> built = List.copyOf(tools);
        validate(built);
        return built;
    }

    private static ToolSpec.Annotations conservativeAnnotations() {
        return new ToolSpec.Annotations(false, true, false, true);
    }

    static ToolSpec literalized(ToolSpec tool, String... fields) {
        Map<String, String> claims = new LinkedHashMap<>();
        for (String field : fields) {
            claims.put(field, "double-hash-once");
        }
        return tool.withInputLiteralization(claims);
    }

    @SafeVarargs
    private static <E extends Enum<E>> Set<E> enums(E first, E... rest) {
        Set<E> values = EnumSet.noneOf(first.getDeclaringClass());
        values.add(first);
        for (E value : rest) {
            values.add(value);
        }
        return values;
    }

    static Set<ToolSpec.TmuxEffect> effects(ToolSpec.TmuxEffect first, ToolSpec.TmuxEffect... rest) {
        return enums(first, rest);
    }

    static Set<ToolSpec.OutputClass> outputs(ToolSpec.OutputClass first, ToolSpec.OutputClass... rest) {
        return enums(first, rest);
    }

    static Input input(String name, ToolSpec.InputSink first, ToolSpec.InputSink... rest) {
        return new Input(name, enums(first, rest));
    }

    static Map<String, Set<ToolSpec.InputSink>> sinks(Input... inputs) {
        Map<String, Set<ToolSpec.InputSink>> sinks = new LinkedHashMap<>();
        for (Input input : inputs) {
            if (sinks.put(input.name(), input.sinks()) != null) {
                throw new IllegalArgumentException("duplicate sink declaration for '" + input.name() + "'");
            }
        }
        return sinks;
    }

    static OutputSchema shape(OutputSchema.Field first, OutputSchema.Field... rest) {
        return OutputSchema.of(first, rest);
    }

    static OutputSchema record(Class<?> type, String... optionalFields) {
        return OutputSchema.ofRecord(type).withOptionalFields(optionalFields);
    }

    static OutputSchema.Field field(String name, OutputSchema.ValueType type) {
        return new OutputSchema.Field(name, type);
    }

    static Map<String, Object> arrayOf(Map<String, Object> item) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", item);
        return java.util.Collections.unmodifiableMap(schema);
    }

    static ToolSpec tool(
            String name,
            String title,
            String details,
            ToolSpec.Toolset toolset,
            ToolSpec.ProcessReach processReach,
            Set<ToolSpec.TmuxEffect> effects,
            Set<ToolSpec.OutputClass> outputs,
            boolean mayExposeSecrets,
            boolean mayReturnUntrustedContent,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return tool(
                name,
                title,
                details,
                toolset,
                processReach,
                effects,
                outputs,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                arguments,
                sinks,
                Set.of(),
                output,
                answer);
    }

    static ToolSpec tool(
            String name,
            String title,
            String details,
            ToolSpec.Toolset toolset,
            ToolSpec.ProcessReach processReach,
            Set<ToolSpec.TmuxEffect> effects,
            Set<ToolSpec.OutputClass> outputs,
            boolean mayExposeSecrets,
            boolean mayReturnUntrustedContent,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            Set<String> nestedAuthority,
            OutputSchema output,
            java.util.function.Function<Call, Object> answer) {
        return ToolSpec.define(
                name,
                title,
                details,
                toolset,
                processReach,
                effects,
                outputs,
                mayExposeSecrets,
                mayReturnUntrustedContent,
                conservativeAnnotations(),
                arguments,
                sinks,
                nestedAuthority,
                output,
                answer);
    }

    private static void validateSchema(ToolSpec tool) {
        Set<String> schema = new LinkedHashSet<>();
        for (Argument argument : tool.arguments()) {
            if (!schema.add(argument.name())) {
                throw new IllegalArgumentException(
                        tool.name() + " has duplicate schema field '" + argument.name() + "'");
            }
            if (Set.of("socket", "socket_name", "socket_path").contains(argument.name())) {
                throw new IllegalArgumentException(tool.name() + " exposes a per-call socket selector");
            }
        }
        if (!schema.equals(tool.inputSinks().keySet())) {
            Set<String> missing = new HashSet<>(schema);
            missing.removeAll(tool.inputSinks().keySet());
            Set<String> extra = new HashSet<>(tool.inputSinks().keySet());
            extra.removeAll(schema);
            throw new IllegalArgumentException(
                    tool.name() + " sink/schema mismatch; missing=" + missing + ", extra=" + extra);
        }
    }

    private static void validateReach(ToolSpec tool) {
        Set<ToolSpec.InputSink> sinks = tool.inputSinks().values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean paneInput = sinks.contains(ToolSpec.InputSink.PANE_INPUT);
        boolean shellCommand = sinks.contains(SHELL_COMMAND);
        boolean processArgv = sinks.contains(ToolSpec.InputSink.PROCESS_ARGV);
        switch (tool.processReach()) {
            case NONE -> {
                if (paneInput || shellCommand || processArgv) {
                    throw new IllegalArgumentException(tool.name() + " has executable sinks with reach none");
                }
            }
            case CONFIGURED_PROCESS -> {
                if (paneInput || shellCommand || processArgv) {
                    throw new IllegalArgumentException(tool.name() + " misstates configured-process reach");
                }
            }
            case PANE_INPUT -> {
                if (!paneInput || shellCommand || processArgv) {
                    throw new IllegalArgumentException(tool.name() + " pane-input reach disagrees with its sinks");
                }
            }
            case PANE_COMMAND -> {
                if (!shellCommand || processArgv) {
                    throw new IllegalArgumentException(
                            tool.name() + " pane-command reach disagrees with its shell-command sink");
                }
            }
            case HOST_COMMAND -> throw new IllegalArgumentException(tool.name() + " exposes host-command reach");
        }
        if (tool.toolset() == INSPECT
                && (tool.processReach() != NONE
                        || !tool.effects().contains(OBSERVE)
                        || tool.effects().contains(DELETE))) {
            throw new IllegalArgumentException(tool.name() + " is not observational inspect authority");
        }
        if (tool.toolset() == MANAGE && tool.processReach() != NONE) {
            throw new IllegalArgumentException(tool.name() + " manage authority reaches a workload process");
        }
        if (tool.toolset() == EXECUTE
                && tool.processReach() == NONE
                && !tool.name().equals("set_synchronize_panes")) {
            throw new IllegalArgumentException(tool.name() + " execute authority has no process reach");
        }
        if (tool.toolset() == TEARDOWN
                && (tool.processReach() != NONE || !tool.effects().contains(DELETE))) {
            throw new IllegalArgumentException(tool.name() + " is not direct teardown authority");
        }
    }

    record Input(String name, Set<ToolSpec.InputSink> sinks) {
        Input {
            Objects.requireNonNull(name, "name");
            sinks = Set.copyOf(sinks);
        }
    }
}
