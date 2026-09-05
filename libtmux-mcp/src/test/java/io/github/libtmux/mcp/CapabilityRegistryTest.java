package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The capability registry's client-visible claims, selection, and disclosure contract. */
final class CapabilityRegistryTest {

    private static final List<String> CATALOG_ORDER = List.of(
            "list_sessions",
            "list_windows",
            "list_panes",
            "get_server_info",
            "get_session_info",
            "get_window_info",
            "get_pane_info",
            "capture_pane",
            "capture_since",
            "snapshot_pane",
            "search_panes",
            "find_pane_by_position",
            "wait_for_text",
            "get_tmux_variables",
            "show_option",
            "show_environment",
            "show_hooks",
            "call_read_tools_batch",
            "rename_session",
            "rename_window",
            "select_window",
            "select_pane",
            "select_layout",
            "resize_window",
            "resize_pane",
            "move_window",
            "swap_pane",
            "set_pane_title",
            "enter_copy_mode",
            "exit_copy_mode",
            "wait_for_channel",
            "signal_channel",
            "set_mouse_enabled",
            "set_history_limit",
            "create_session",
            "create_window",
            "split_window",
            "respawn_pane",
            "run_shell_command",
            "send_keys",
            "send_keys_batch",
            "paste_text",
            "set_synchronize_panes",
            "clear_pane_scrollback",
            "kill_pane",
            "kill_window",
            "kill_session");

    private static final Map<String, Set<String>> EXPECTED_TOOLSETS = Map.of(
            "inspect",
            Set.of(
                    "list_sessions",
                    "list_windows",
                    "list_panes",
                    "get_server_info",
                    "get_session_info",
                    "get_window_info",
                    "get_pane_info",
                    "capture_pane",
                    "capture_since",
                    "snapshot_pane",
                    "search_panes",
                    "find_pane_by_position",
                    "wait_for_text",
                    "get_tmux_variables",
                    "show_option",
                    "show_environment",
                    "show_hooks",
                    "call_read_tools_batch"),
            "manage",
            Set.of(
                    "rename_session",
                    "rename_window",
                    "select_window",
                    "select_pane",
                    "select_layout",
                    "resize_window",
                    "resize_pane",
                    "move_window",
                    "swap_pane",
                    "set_pane_title",
                    "enter_copy_mode",
                    "exit_copy_mode",
                    "wait_for_channel",
                    "signal_channel",
                    "set_mouse_enabled",
                    "set_history_limit"),
            "execute",
            Set.of(
                    "create_session",
                    "create_window",
                    "split_window",
                    "respawn_pane",
                    "run_shell_command",
                    "send_keys",
                    "send_keys_batch",
                    "paste_text",
                    "set_synchronize_panes"),
            "teardown",
            Set.of("clear_pane_scrollback", "kill_pane", "kill_window", "kill_session"));

    @Test
    void everyManifestRowDrivesConservativeRegistrationMetadataAndSinkValidation() {
        assertEquals(CATALOG_ORDER, Catalog.tools().stream().map(ToolSpec::name).toList());
        Catalog.validate(Catalog.tools());
        ToolSurface all = ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "inspect,manage,execute,teardown"));

        for (ToolSpec tool : Catalog.tools()) {
            McpSchema.Tool described = tool.describe();
            assertTrue(
                    Objects.requireNonNull(described.description(), "description")
                            .startsWith(tool.controlledOpener()),
                    tool.name());
            McpSchema.ToolAnnotations annotations = Objects.requireNonNull(described.annotations(), "annotations");
            assertEquals(false, annotations.readOnlyHint(), tool.name());
            assertEquals(true, annotations.destructiveHint(), tool.name());
            assertEquals(false, annotations.idempotentHint(), tool.name());
            assertEquals(true, annotations.openWorldHint(), tool.name());
            assertEquals(
                    tool.arguments().stream().map(Argument::name).collect(java.util.stream.Collectors.toSet()),
                    tool.inputSinks().keySet(),
                    tool.name());
            assertTrue(tool.effects().size() >= 1, tool.name());
            assertTrue(tool.outputClasses().size() >= 1, tool.name());
            assertFalse(tool.outputSchema().isEmpty(), tool.name());
            assertFalse(tool.processReach() == ToolSpec.ProcessReach.HOST_COMMAND, tool.name());
            assertEquals(tool.name().equals("set_synchronize_panes"), tool.amplifiesFutureInput(), tool.name());
            assertEquals(
                    Set.of("com.git-pull.libtmux-mcp/capability"),
                    described.meta().keySet(),
                    tool.name());
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) Objects.requireNonNull(
                    described.meta().get("com.git-pull.libtmux-mcp/capability"), "capability metadata");
            assertEquals(
                    Set.of(
                            "name",
                            "title",
                            "description",
                            "toolset",
                            "processReach",
                            "tmuxEffects",
                            "outputClasses",
                            "mayExposeSecrets",
                            "mayReturnUntrustedContent",
                            "amplifiesFutureInput",
                            "annotations",
                            "inputSchema",
                            "outputSchema",
                            "inputLiteralization",
                            "nestedAuthority"),
                    metadata.keySet(),
                    tool.name());
            assertEquals(wireNames(tool.effects()), metadata.get("tmuxEffects"), tool.name());
            assertEquals(tool.outputSchema(), described.outputSchema(), tool.name());
            assertEquals(tool.outputSchema(), metadata.get("outputSchema"), tool.name());
            assertEquals(tool.amplifiesFutureInput(), metadata.get("amplifiesFutureInput"), tool.name());
            Set<String> formatInputs = tool.inputSinks().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(ToolSpec.InputSink.TMUX_FORMAT))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(formatInputs, tool.inputLiteralization().keySet(), tool.name());
            assertTrue(
                    tool.inputLiteralization().values().stream()
                            .allMatch(Set.of("double-hash-once", "validated-variable-name")::contains),
                    tool.name());
            tool.inputLiteralization()
                    .forEach((input, strategy) -> assertTrue(
                            Objects.requireNonNull(tool.inputSinks().get(input), input)
                                    .contains(
                                            strategy.equals("double-hash-once")
                                                    ? ToolSpec.InputSink.TMUX_STATE
                                                    : ToolSpec.InputSink.TMUX_LOOKUP),
                            tool.name() + "." + input));
            assertSame(tool, all.require(tool.name()));
        }

        assertEquals(47, Catalog.tools().size());
        assertEquals(
                Map.of("inspect", 18L, "manage", 16L, "execute", 9L, "teardown", 4L),
                Catalog.tools().stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                tool -> tool.toolset().wireName(), java.util.stream.Collectors.counting())));

        for (String creator : List.of("create_session", "create_window", "split_window", "respawn_pane")) {
            ToolSpec tool = byName(creator);
            assertEquals(ToolSpec.ProcessReach.CONFIGURED_PROCESS, tool.processReach(), creator);
            assertTrue(
                    tool.arguments().stream()
                            .noneMatch(argument -> argument.name().equals("command")),
                    creator);
            assertTrue(
                    tool.arguments().stream()
                            .noneMatch(argument -> argument.name().equals("environment")),
                    creator);
        }
        assertEquals(
                Set.of("session_name", "window_name", "start_directory", "width", "height"),
                schemaKeys("create_session"));
        assertEquals(
                Set.of("session_id", "window_name", "start_directory", "attach", "direction"),
                schemaKeys("create_window"));
        assertEquals(Set.of("pane_id", "direction", "percent", "start_directory"), schemaKeys("split_window"));
        assertEquals(Set.of("pane_id", "start_directory"), schemaKeys("respawn_pane"));
        assertEquals(
                Set.of("new_name"),
                byName("rename_session").inputLiteralization().keySet());
        assertEquals(
                Set.of("session_name", "window_name", "start_directory"),
                byName("create_session").inputLiteralization().keySet());
        assertEquals(
                Map.of("names", "validated-variable-name"),
                byName("get_tmux_variables").inputLiteralization());
        assertEquals(
                Set.of(ToolSpec.InputSink.TMUX_LOOKUP, ToolSpec.InputSink.TMUX_FORMAT),
                byName("get_tmux_variables").inputSinks().get("names"));
        assertEquals(
                Set.of(ToolSpec.InputSink.TMUX_LOOKUP),
                byName("get_tmux_variables").inputSinks().get("pane"));
        assertEquals(Set.of("names", "pane"), schemaKeys("get_tmux_variables"));
        @SuppressWarnings("unchecked")
        Map<String, Object> variableProperties = (Map<String, Object>) Objects.requireNonNull(
                byName("get_tmux_variables").inputSchema().get("properties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> namesSchema = (Map<String, Object>) Objects.requireNonNull(variableProperties.get("names"));
        assertEquals(32, namesSchema.get("maxItems"));
        assertEquals(Set.of("operations", "onError"), schemaKeys("send_keys_batch"));
        ToolSpec batch = byName("call_read_tools_batch");
        assertEquals(16, batch.nestedAuthority().size());
        assertTrue(batch.nestedAuthority().contains("show_environment"));
        assertFalse(batch.nestedAuthority().contains("wait_for_text"));
        assertTrue(batch.outputClasses().contains(ToolSpec.OutputClass.PROCESS_ENVIRONMENT));
        assertTrue(batch.controlledOpener().startsWith("Read pane output"));
        assertEquals(Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.CHANGE), batch.effects());
        assertEquals(Set.of(ToolSpec.InputSink.NESTED_TOOL), batch.inputSinks().get("operations"));
        assertTrue(batch.description().contains("no separate approval"));
        assertTrue(batch.description().contains("1 MiB"));
        assertTrue(byName("set_synchronize_panes").description().contains("subsequent input is copied to every pane"));
        for (String removed : List.of(
                "tmux_whoami",
                "tmux_list_servers",
                "tmux_apply_workspace",
                "tmux_set_option",
                "tmux_drain_channel",
                "tmux_kill")) {
            assertFalse(CATALOG_ORDER.contains(removed), removed);
        }
        assertThrows(UnsupportedOperationException.class, () -> Catalog.tools().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(byName("get_server_info"), byName("get_server_info"))));
    }

    @Test
    void exactEffectRowsAndExclusionPrunedBatchUnionsStayAligned() {
        Map<String, Set<ToolSpec.TmuxEffect>> expected = Map.ofEntries(
                Map.entry("capture_since", Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("create_session", Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("enter_copy_mode", Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("kill_pane", Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.DELETE)),
                Map.entry(
                        "respawn_pane",
                        Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.CHANGE, ToolSpec.TmuxEffect.DELETE)),
                Map.entry("run_shell_command", Set.of(ToolSpec.TmuxEffect.OBSERVE, ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("set_history_limit", Set.of(ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("set_mouse_enabled", Set.of(ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("set_synchronize_panes", Set.of(ToolSpec.TmuxEffect.CHANGE)),
                Map.entry("wait_for_channel", Set.of(ToolSpec.TmuxEffect.CHANGE)));
        expected.forEach((name, effects) -> assertEquals(effects, byName(name).effects(), name));
        assertEquals(
                Set.of(ToolSpec.OutputClass.CONFIGURED_COMMAND),
                byName("show_hooks").outputClasses());

        ToolSurface pruned = ToolSurface.resolve(Map.of(
                ToolSurface.TOOLSETS_ENV,
                "",
                ToolSurface.TOOLS_ENV,
                "call_read_tools_batch",
                ToolSurface.EXCLUDE_TOOLS_ENV,
                "capture_since,show_environment,show_hooks,show_option,get_tmux_variables"));
        ToolSpec batch = pruned.require("call_read_tools_batch");
        assertEquals(Set.of(ToolSpec.TmuxEffect.OBSERVE), batch.effects());
        assertEquals(
                Set.of(ToolSpec.OutputClass.TMUX_METADATA, ToolSpec.OutputClass.TERMINAL_CONTENT),
                batch.outputClasses());
        assertTrue(batch.controlledOpener().startsWith("Read pane output"));

        ToolSurface empty = ToolSurface.resolve(Map.of(
                ToolSurface.TOOLSETS_ENV,
                "",
                ToolSurface.TOOLS_ENV,
                "call_read_tools_batch",
                ToolSurface.EXCLUDE_TOOLS_ENV,
                String.join(",", byName("call_read_tools_batch").nestedAuthority())));
        ToolSpec emptyBatch = empty.require("call_read_tools_batch");
        assertEquals(Set.of(ToolSpec.TmuxEffect.OBSERVE), emptyBatch.effects());
        assertEquals(Set.of(), emptyBatch.outputClasses());
        assertFalse(emptyBatch.mayExposeSecrets());
        assertFalse(emptyBatch.mayReturnUntrustedContent());
    }

    @Test
    void allSixteenUnorderedToolsetSubsetsResolveExactlyAndDeterministically() {
        List<String> names = List.of("inspect", "manage", "execute", "teardown");
        for (int mask = 0; mask < 16; mask++) {
            List<String> selected = new ArrayList<>();
            Set<String> expectedNames = new LinkedHashSet<>();
            for (int bit = 0; bit < names.size(); bit++) {
                if ((mask & (1 << bit)) != 0) {
                    String name = names.get(bit);
                    selected.add(name);
                    expectedNames.addAll(EXPECTED_TOOLSETS.get(name));
                }
            }
            List<String> expected =
                    CATALOG_ORDER.stream().filter(expectedNames::contains).toList();
            ToolSurface surface = ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, String.join(",", selected)));

            assertEquals(expected, List.copyOf(surface.tools().keySet()), "mask=" + mask);
            assertEquals(
                    expected,
                    surface.tools().keySet().stream()
                            .map(surface::require)
                            .map(ToolSpec::name)
                            .toList());
        }

        assertEquals(
                ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "inspect,manage"))
                        .tools()
                        .keySet(),
                ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "manage,inspect"))
                        .tools()
                        .keySet());
        assertFalse(ToolSurface.defaults().tools().containsKey("kill_session"));
    }

    @Test
    void namedSelectionIsFailClosedAndExclusionAlwaysWins() {
        ToolSurface selected = ToolSurface.resolve(Map.of(
                ToolSurface.TOOLSETS_ENV,
                "",
                ToolSurface.TOOLS_ENV,
                "run_shell_command,kill_session",
                ToolSurface.EXCLUDE_TOOLS_ENV,
                "run_shell_command"));
        assertEquals(List.of("kill_session"), List.copyOf(selected.tools().keySet()));
        assertThrows(IllegalArgumentException.class, () -> selected.require("run_shell_command"));

        for (String malformed : List.of(",inspect", "inspect,", "inspect,,manage", " ")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, malformed)),
                    malformed);
        }
        for (String variable : List.of(ToolSurface.TOOLS_ENV, ToolSurface.EXCLUDE_TOOLS_ENV)) {
            assertThrows(IllegalArgumentException.class, () -> ToolSurface.resolve(Map.of(variable, "")), variable);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ToolSurface.resolve(Map.of(variable, "run_shell_command,")),
                    variable);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ToolSurface.resolve(Map.of(variable, "not_a_real_tool")),
                    variable);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "inspect,admin")));
        IllegalArgumentException legacy = assertThrows(
                IllegalArgumentException.class, () -> ToolSurface.resolve(Map.of(ToolSurface.LEGACY_SAFETY_ENV, "")));
        assertTrue(String.valueOf(legacy.getMessage()).contains(ToolSurface.TOOLSETS_ENV));
        assertThrows(UnsupportedOperationException.class, () -> selected.tools().clear());

        ToolSurface pruned = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "inspect", ToolSurface.EXCLUDE_TOOLS_ENV, "capture_pane"));
        assertTrue(pruned.tools().containsKey("call_read_tools_batch"));
        assertFalse(pruned.require("call_read_tools_batch").nestedAuthority().contains("capture_pane"));
        assertFalse(
                pruned.require("call_read_tools_batch").inputSchema().toString().contains("capture_pane"));
        assertTrue(
                pruned.require("call_read_tools_batch").inputSchema().toString().contains("show_environment"));

        ToolSurface aggregateOnly = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "call_read_tools_batch"));
        assertEquals(
                16,
                aggregateOnly.require("call_read_tools_batch").nestedAuthority().size());
        assertEquals(16, batchMaxItems(aggregateOnly.require("call_read_tools_batch")));
        ToolSurface emptyAggregate = ToolSurface.resolve(Map.of(
                ToolSurface.TOOLSETS_ENV,
                "",
                ToolSurface.TOOLS_ENV,
                "call_read_tools_batch",
                ToolSurface.EXCLUDE_TOOLS_ENV,
                String.join(",", aggregateOnly.require("call_read_tools_batch").nestedAuthority())));
        assertEquals(
                0,
                emptyAggregate
                        .require("call_read_tools_batch")
                        .nestedAuthority()
                        .size());
        assertEquals(16, batchMaxItems(emptyAggregate.require("call_read_tools_batch")));
        assertFalse(emptyAggregate
                .require("call_read_tools_batch")
                .inputSchema()
                .toString()
                .contains("oneOf=[]"));
    }

    @Test
    void theRetiredWatchVariableFailsWithCurrentAlternatives() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> ToolSurface.resolve(Map.of("LIBTMUX_WATCH", "")));

        String message = String.valueOf(refused.getMessage());
        for (String replacement : List.of("wait_for_text", "wait_for_channel", "capture_since", "ControlClient")) {
            assertTrue(message.contains(replacement), message);
        }
    }

    @Test
    void invalidCatalogClaimsFailClosed() {
        ToolSpec whoami = byName("get_server_info");
        Argument unexpected = Argument.required("unexpected", "A test-only input.");
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        whoami,
                        whoami.processReach(),
                        List.of(unexpected),
                        Map.of(),
                        Set.of(),
                        whoami.description()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        whoami,
                        whoami.processReach(),
                        List.of(),
                        Map.of("unexpected", Set.of(ToolSpec.InputSink.NONE)),
                        Set.of(),
                        whoami.description()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        whoami,
                        ToolSpec.ProcessReach.HOST_COMMAND,
                        whoami.arguments(),
                        whoami.inputSinks(),
                        Set.of(),
                        whoami.description()))));

        ToolSpec windows = byName("list_windows");
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        windows,
                        windows.processReach(),
                        windows.arguments(),
                        Map.of("session", Set.of(ToolSpec.InputSink.TMUX_FORMAT)),
                        Set.of(),
                        windows.description()))));

        ToolSpec rename = byName("rename_session");
        Map<String, Set<ToolSpec.InputSink>> understated = new java.util.LinkedHashMap<>(rename.inputSinks());
        understated.put("new_name", Set.of(ToolSpec.InputSink.TMUX_FORMAT));
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        rename,
                        rename.processReach(),
                        rename.arguments(),
                        understated,
                        Set.of(),
                        rename.description()))));

        ToolSpec creator = byName("create_session");
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        creator,
                        ToolSpec.ProcessReach.CONFIGURED_PROCESS,
                        creator.arguments(),
                        Map.of("name", Set.of(ToolSpec.InputSink.PROCESS_ARGV)),
                        Set.of(),
                        creator.description()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        whoami,
                        whoami.processReach(),
                        whoami.arguments(),
                        whoami.inputSinks(),
                        Set.of("missing_nested_tool"),
                        whoami.description()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> Catalog.validate(List.of(replace(
                        whoami,
                        whoami.processReach(),
                        whoami.arguments(),
                        whoami.inputSinks(),
                        Set.of(),
                        "Uncontrolled description."))));
    }

    @Test
    void nestedCallsUseTheSameClosedTypedSchemaAsTopLevelCalls() {
        ToolSpec option = byName("show_option");
        assertThrows(IllegalArgumentException.class, () -> option.validateArguments(Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> option.validateArguments(Map.of("name", "status", "unexpected", true)));
        assertThrows(IllegalArgumentException.class, () -> option.validateArguments(Map.of("name", 7)));
        option.validateArguments(Map.of("name", "status"));

        ToolSpec wait = byName("wait_for_channel");
        assertThrows(
                IllegalArgumentException.class,
                () -> wait.validateArguments(Map.of("channel", "ready", "timeout", "soon")));
    }

    @Test
    void outputSchemasRequireKnownFieldsAndTypeNestedArrays() {
        for (ToolSpec tool : Catalog.tools()) {
            Map<String, Object> schema = tool.outputSchema();
            Map<String, Object> properties = object(schema.get("properties"), tool.name() + " properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) Objects.requireNonNull(schema.get("required"), tool.name());
            assertFalse(required.isEmpty(), tool.name());
            assertTrue(properties.keySet().containsAll(required), tool.name());
        }

        Map<String, Object> captureProperties =
                object(byName("capture_pane").outputSchema().get("properties"), "capture properties");
        Map<String, Object> content = object(captureProperties.get("content"), "content");
        assertEquals(Map.of("type", "string"), content.get("items"));

        Map<String, Object> searchProperties =
                object(byName("search_panes").outputSchema().get("properties"), "search properties");
        Map<String, Object> matches = object(searchProperties.get("matches"), "matches");
        Map<String, Object> hit = object(matches.get("items"), "match item");
        Map<String, Object> hitProperties = object(hit.get("properties"), "match properties");
        assertEquals(Set.of("pane_id", "session", "window", "line"), hitProperties.keySet());

        Map<String, Object> batchProperties =
                object(byName("call_read_tools_batch").outputSchema().get("properties"), "batch properties");
        Map<String, Object> results = object(batchProperties.get("results"), "results");
        Map<String, Object> row = object(results.get("items"), "result item");
        Map<String, Object> rowProperties = object(row.get("properties"), "result properties");
        assertEquals(Set.of("index", "tool", "success", "error", "result", "resultTruncated"), rowProperties.keySet());
        assertEquals(rowProperties.keySet(), new LinkedHashSet<>(strings(row.get("required"), "result required")));
        Map<String, Object> envelopeOrNull = object(rowProperties.get("result"), "result envelope");
        assertTrue(envelopeOrNull.containsKey("oneOf"));

        Map<String, Object> sendProperties =
                object(byName("send_keys_batch").outputSchema().get("properties"), "send properties");
        Map<String, Object> sends = object(sendProperties.get("results"), "send results");
        Map<String, Object> sendRow = object(sends.get("items"), "send result item");
        assertEquals("object", sendRow.get("type"));

        Object nil = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        Map<String, Object> malformedRow = Map.of(
                "index",
                "zero",
                "tool",
                "get_server_info",
                "success",
                true,
                "error",
                nil,
                "result",
                nil,
                "resultTruncated",
                false);
        Map<String, Object> malformedBatch = Map.of(
                "results",
                List.of(malformedRow),
                "succeeded",
                1,
                "failed",
                0,
                "stoppedAt",
                nil,
                "truncated",
                false,
                "truncatedBytes",
                0,
                "onError",
                "stop");
        assertThrows(
                IllegalStateException.class,
                () -> byName("call_read_tools_batch").validateOutput(malformedBatch));
    }

    @Test
    void readmeInventoryIsGeneratedFromTheAuthoritativeCatalog() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path readme = workingDirectory.resolve("libtmux-mcp/README.md");
        if (!Files.isRegularFile(readme)) {
            readme = workingDirectory.resolve("README.md");
        }
        String documentation = Files.readString(readme);
        String start = "<!-- BEGIN GENERATED TOOL INVENTORY -->";
        String end = "<!-- END GENERATED TOOL INVENTORY -->";
        int first = documentation.indexOf(start);
        int last = documentation.indexOf(end);
        assertTrue(first >= 0 && last > first, "generated inventory markers are missing");
        assertEquals(generatedInventory(), documentation.substring(first, last + end.length()));
    }

    @Test
    void anAggregateIncludedAloneDispatchesRepeatedNestedCallsWithoutAdvertisingThem() {
        ToolSurface surface = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "call_read_tools_batch"));
        TmuxTransport absent = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(1, List.of(), List.of("absent"));
            }

            @Override
            public void close() {}
        };
        List<Map<String, Object>> operations = java.util.stream.IntStream.range(0, 16)
                .mapToObj(ignored -> Map.<String, Object>of("tool", "get_server_info"))
                .toList();
        try (Server server = Server.using(ServerConfig.builder().build(), absent)) {
            Connection connection = new Connection(server, Caller.nowhere(), surface);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) Operations.callReadToolsBatch(
                    connection.call(Map.of("operations", operations), Call.Progress.SILENT));

            assertEquals(16, result.get("succeeded"));
            assertEquals(0, result.get("failed"));
            assertEquals(false, result.get("truncated"));
            assertEquals(0, result.get("truncatedBytes"));
            assertEquals(com.fasterxml.jackson.databind.node.NullNode.getInstance(), result.get("stoppedAt"));
            assertEquals("stop", result.get("onError"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) Objects.requireNonNull(result.get("results"), "results");
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope =
                    (Map<String, Object>) Objects.requireNonNull(rows.getFirst().get("result"), "result");
            assertEquals(0, rows.getFirst().get("index"));
            assertEquals(true, rows.getFirst().get("success"));
            assertEquals(false, rows.getFirst().get("resultTruncated"));
            assertTrue(rows.getFirst().containsKey("error"));
            assertEquals(
                    com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                    rows.getFirst().get("error"));
            assertTrue(envelope.get("content") instanceof List<?>);
            assertTrue(envelope.get("structuredContent") instanceof Map<?, ?>);
            assertEquals(false, envelope.get("isError"));
            assertEquals(Set.of("call_read_tools_batch"), surface.tools().keySet());
        }
    }

    @Test
    void readBatchUsesCamelCaseOnErrorForDispatchAndDisclosure() {
        ToolSurface surface = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "call_read_tools_batch"));
        TmuxTransport absent = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(1, List.of(), List.of("absent"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), absent)) {
            Connection connection = new Connection(server, Caller.nowhere(), surface);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) Operations.callReadToolsBatch(connection.call(
                    Map.of(
                            "operations",
                            List.of(
                                    Map.of("tool", "get_pane_info", "arguments", Map.of("pane_id", "%404")),
                                    Map.of("tool", "get_server_info")),
                            "onError",
                            "continue"),
                    Call.Progress.SILENT));

            assertEquals("continue", result.get("onError"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) Objects.requireNonNull(result.get("results"));
            assertEquals(2, rows.size());
            assertEquals(false, rows.getFirst().get("success"));
            assertTrue(rows.getFirst().get("error") instanceof String);
            assertEquals(true, rows.get(1).get("success"));

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) Objects.requireNonNull(
                    surface.require("call_read_tools_batch").inputSchema().get("properties"));
            assertTrue(properties.containsKey("onError"));
            assertFalse(properties.containsKey("on_error"));
        }
    }

    @Test
    void aggregateOutputStopsAtOneMiBAndReportsTruncation() throws Exception {
        ToolSurface surface = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "call_read_tools_batch"));
        TmuxTransport oversizedEnvironment = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(0, List.of("VALUE=" + "x".repeat(1_048_576)), List.of());
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), oversizedEnvironment)) {
            Connection connection = new Connection(server, Caller.nowhere(), surface);
            ToolSpec environment = byName("show_environment");
            Object nested = environment.answer().apply(connection.call(Map.of(), Call.Progress.SILENT));
            int expectedRemovedBytes = Answers.JSON.writeValueAsBytes(Answers.envelope(Answers.ok(nested))).length
                    - Answers.JSON.writeValueAsBytes(com.fasterxml.jackson.databind.node.NullNode.getInstance()).length;
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) Operations.callReadToolsBatch(connection.call(
                    Map.of("operations", List.of(Map.of("tool", "show_environment"))), Call.Progress.SILENT));

            byName("call_read_tools_batch").validateOutput(result);
            assertEquals(1, result.get("succeeded"));
            assertEquals(0, result.get("failed"));
            assertEquals(true, result.get("truncated"));
            assertEquals(expectedRemovedBytes, result.get("truncatedBytes"));
            assertEquals(com.fasterxml.jackson.databind.node.NullNode.getInstance(), result.get("stoppedAt"));
            assertEquals("stop", result.get("onError"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) Objects.requireNonNull(result.get("results"), "results");
            assertEquals(1, rows.size());
            assertEquals(true, rows.getFirst().get("resultTruncated"));
            assertEquals(
                    com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                    rows.getFirst().get("result"));
            assertTrue(
                    Answers.JSON.writeValueAsBytes(Answers.envelope(Answers.ok(result))).length <= 1_048_576,
                    "the complete duplicated MCP tool result must fit the cap");
        }
    }

    @Test
    void aggregateOverflowRollsBackOnlyTheRowThatCrossedTheCap() {
        ToolSurface surface = ToolSurface.resolve(
                Map.of(ToolSurface.TOOLSETS_ENV, "", ToolSurface.TOOLS_ENV, "call_read_tools_batch"));
        AtomicInteger calls = new AtomicInteger();
        TmuxTransport secondResultIsOversized = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return calls.getAndIncrement() == 0
                        ? new CommandResult(1, List.of(), List.of("absent"))
                        : new CommandResult(0, List.of("VALUE=" + "x".repeat(1_048_576)), List.of());
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), secondResultIsOversized)) {
            Connection connection = new Connection(server, Caller.nowhere(), surface);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) Operations.callReadToolsBatch(connection.call(
                    Map.of(
                            "operations",
                            List.of(Map.of("tool", "get_server_info"), Map.of("tool", "show_environment"))),
                    Call.Progress.SILENT));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) Objects.requireNonNull(result.get("results"), "results");

            assertEquals(2, rows.size());
            assertTrue(rows.get(0).get("result") instanceof Map<?, ?>);
            assertEquals(false, rows.get(0).get("resultTruncated"));
            assertEquals(
                    com.fasterxml.jackson.databind.node.NullNode.getInstance(),
                    rows.get(1).get("result"));
            assertEquals(true, rows.get(1).get("resultTruncated"));
        }
    }

    @Test
    void theOnlyResourceDisclosesTheEffectiveSurfaceAndCurrentSocketHonestly() {
        TmuxTransport absent = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(1, List.of(), List.of("absent"));
            }

            @Override
            public void close() {}
        };
        ToolSurface surface = ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, "inspect,teardown"));
        try (Server server = Server.using(ServerConfig.builder().build(), absent)) {
            Map<String, Object> report = surface.capabilities(server);
            @SuppressWarnings("unchecked")
            Map<String, Object> socket = (Map<String, Object>) Objects.requireNonNull(report.get("socket"), "socket");
            assertEquals("inherit", socket.get("selector"));
            assertEquals("inherited", socket.get("selectionProvenance"));
            assertEquals("unknown", socket.get("serverState"));
            assertEquals("unknown", socket.get("configurationProvenance"));
            assertEquals(surface.tools().size(), report.get("toolCount"));
            assertEquals("tmux-user", report.get("executionAuthority"));
            assertEquals("none", report.get("operatingSystemBoundary"));
            assertEquals(
                    Map.of(
                            "oneSocketPerProcess", true,
                            "perCallSocketSelection", false,
                            "hostCommandExecution", false,
                            "dynamicResources", false),
                    report.get("boundary"));
            @SuppressWarnings("unchecked")
            Map<String, Object> connectionFacts =
                    (Map<String, Object>) Objects.requireNonNull(report.get("connection"), "connection");
            assertEquals(
                    Set.of(
                            "socketSelector",
                            "socketProvenance",
                            "resolvedSocketPath",
                            "serverState",
                            "configurationProvenance",
                            "attachCommand"),
                    connectionFacts.keySet());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reportedTools =
                    (List<Map<String, Object>>) Objects.requireNonNull(report.get("tools"), "tools");
            assertEquals(
                    List.copyOf(surface.tools().keySet()),
                    reportedTools.stream().map(tool -> tool.get("name")).toList());
            for (ToolSpec tool : surface.tools().values()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> advertised = (Map<String, Object>) Objects.requireNonNull(
                        tool.describe().meta().get(ToolSpec.CAPABILITY_META_KEY), "advertised capability");
                Map<String, Object> reported = reportedTools.stream()
                        .filter(row -> tool.name().equals(row.get("name")))
                        .findFirst()
                        .orElseThrow();
                assertEquals(reported, advertised, tool.name());
            }

            Connection connection = new Connection(server, Caller.nowhere(), surface);
            var resources = Resources.fixed(connection);
            assertEquals(1, resources.size());
            assertEquals(
                    Resources.CAPABILITIES_URI, resources.getFirst().resource().uri());
        }
    }

    @Test
    void capabilityReportFreezesOneSocketObservation() {
        AtomicInteger probes = new AtomicInteger();
        TmuxTransport changing = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return probes.getAndIncrement() == 0
                        ? new CommandResult(0, List.of("123"), List.of())
                        : new CommandResult(1, List.of(), List.of("gone"));
            }

            @Override
            public void close() {}
        };
        try (Server server = Server.using(ServerConfig.builder().build(), changing)) {
            Map<String, Object> report = ToolSurface.defaults().capabilities(server);
            @SuppressWarnings("unchecked")
            Map<String, Object> socket = (Map<String, Object>) Objects.requireNonNull(report.get("socket"));
            @SuppressWarnings("unchecked")
            Map<String, Object> connection = (Map<String, Object>) Objects.requireNonNull(report.get("connection"));

            assertEquals(socket.get("serverState"), connection.get("serverState"));
            assertEquals(1, probes.get());
        }
    }

    private static ToolSpec byName(String name) {
        return Catalog.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static int batchMaxItems(ToolSpec batch) {
        Map<String, Object> properties =
                (Map<String, Object>) Objects.requireNonNull(batch.inputSchema().get("properties"), "properties");
        Map<String, Object> operations =
                (Map<String, Object>) Objects.requireNonNull(properties.get("operations"), "operations");
        return (Integer) Objects.requireNonNull(operations.get("maxItems"), "maxItems");
    }

    private static List<String> wireNames(Set<ToolSpec.TmuxEffect> effects) {
        return effects.stream().map(ToolSpec.TmuxEffect::wireName).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError(name + " is not an object: " + value);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(@Nullable Object value, String name) {
        if (!(value instanceof List<?>)) {
            throw new AssertionError(name + " is not an array: " + value);
        }
        return (List<String>) value;
    }

    private static String generatedInventory() {
        StringBuilder inventory = new StringBuilder()
                .append("<!-- BEGIN GENERATED TOOL INVENTORY -->\n")
                .append("The complete frozen inventory below is generated from the code registry.\n\n")
                .append("| toolset | public tools |\n")
                .append("| --- | --- |\n");
        for (ToolSpec.Toolset toolset : ToolSpec.Toolset.values()) {
            String tools = Catalog.tools().stream()
                    .filter(tool -> tool.toolset() == toolset)
                    .map(tool -> "`" + tool.name() + "`")
                    .collect(java.util.stream.Collectors.joining(" · "));
            inventory
                    .append("| `")
                    .append(toolset.wireName())
                    .append("` | ")
                    .append(tools)
                    .append(" |\n");
        }
        return inventory.append("<!-- END GENERATED TOOL INVENTORY -->").toString();
    }

    private static Set<String> schemaKeys(String name) {
        return byName(name).arguments().stream().map(Argument::name).collect(java.util.stream.Collectors.toSet());
    }

    private static ToolSpec replace(
            ToolSpec source,
            ToolSpec.ProcessReach reach,
            List<Argument> arguments,
            Map<String, Set<ToolSpec.InputSink>> sinks,
            Set<String> nestedAuthority,
            String description) {
        return new ToolSpec(
                source.name(),
                source.title(),
                description,
                source.toolset(),
                reach,
                source.effects(),
                source.outputClasses(),
                source.mayExposeSecrets(),
                source.mayReturnUntrustedContent(),
                source.amplifiesFutureInput(),
                source.annotations(),
                arguments,
                sinks,
                source.inputLiteralization(),
                nestedAuthority,
                source.output(),
                source.answer());
    }
}
