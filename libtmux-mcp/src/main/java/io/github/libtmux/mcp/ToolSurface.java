package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The immutable advertised and callable tool surface resolved once at process startup. */
final class ToolSurface {

    static final String TOOLSETS_ENV = "LIBTMUX_TOOLSETS";
    static final String TOOLS_ENV = "LIBTMUX_TOOLS";
    static final String EXCLUDE_TOOLS_ENV = "LIBTMUX_EXCLUDE_TOOLS";
    static final String LEGACY_SAFETY_ENV = "LIBTMUX_SAFETY";
    static final String LEGACY_WATCH_ENV = "LIBTMUX_WATCH";

    private static final Set<ToolSpec.Toolset> INHERITED_DEFAULTS = Collections.unmodifiableSet(
            EnumSet.of(ToolSpec.Toolset.INSPECT, ToolSpec.Toolset.MANAGE, ToolSpec.Toolset.EXECUTE));

    private final Set<ToolSpec.Toolset> toolsets;
    private final Set<String> inclusions;
    private final Set<String> exclusions;
    private final Map<String, ToolSpec> tools;
    private final @Nullable SocketProfile socketProfile;

    private ToolSurface(
            Set<ToolSpec.Toolset> toolsets,
            Set<String> inclusions,
            Set<String> exclusions,
            Map<String, ToolSpec> tools,
            @Nullable SocketProfile socketProfile) {
        EnumSet<ToolSpec.Toolset> copiedToolsets = EnumSet.noneOf(ToolSpec.Toolset.class);
        copiedToolsets.addAll(toolsets);
        this.toolsets = Collections.unmodifiableSet(copiedToolsets);
        this.inclusions = Collections.unmodifiableSet(new LinkedHashSet<>(inclusions));
        this.exclusions = Collections.unmodifiableSet(new LinkedHashSet<>(exclusions));
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.socketProfile = socketProfile;
    }

    static ToolSurface defaults() {
        return resolve(Map.of());
    }

    static ToolSurface resolve(Map<String, String> environment) {
        return resolveInternal(environment, null);
    }

    static ToolSurface resolve(Map<String, String> environment, SocketProfile socketProfile) {
        return resolveInternal(environment, socketProfile);
    }

    private static ToolSurface resolveInternal(Map<String, String> environment, @Nullable SocketProfile socketProfile) {
        if (environment.containsKey(LEGACY_SAFETY_ENV)) {
            throw new IllegalArgumentException(
                    LEGACY_SAFETY_ENV + " was retired; select unordered toolsets with " + TOOLSETS_ENV);
        }
        if (environment.containsKey(LEGACY_WATCH_ENV)) {
            throw new IllegalArgumentException(
                    LEGACY_WATCH_ENV
                            + " was retired; use wait_for_text, wait_for_channel, or capture_since; Java applications can use ControlClient");
        }

        Set<ToolSpec.Toolset> selected = environment.containsKey(TOOLSETS_ENV)
                ? parseToolsets(environment.get(TOOLSETS_ENV))
                : defaults(socketProfile);
        Set<String> included = parseToolNames(environment.get(TOOLS_ENV), TOOLS_ENV);
        Set<String> excluded = parseToolNames(environment.get(EXCLUDE_TOOLS_ENV), EXCLUDE_TOOLS_ENV);

        Map<String, ToolSpec> all = catalogByName();
        rejectUnknownTools(included, all, TOOLS_ENV);
        rejectUnknownTools(excluded, all, EXCLUDE_TOOLS_ENV);

        Map<String, ToolSpec> effective = new LinkedHashMap<>();
        for (ToolSpec tool : Catalog.tools()) {
            if (selected.contains(tool.toolset()) || included.contains(tool.name())) {
                effective.put(tool.name(), tool);
            }
        }
        excluded.forEach(effective::remove);
        Map<String, ToolSpec> bounded = new LinkedHashMap<>();
        for (ToolSpec tool : effective.values()) {
            Set<String> nested = new LinkedHashSet<>(tool.nestedAuthority());
            nested.removeAll(excluded);
            bounded.put(
                    tool.name(), nested.equals(tool.nestedAuthority()) ? tool : tool.withNestedAuthority(nested, all));
        }
        return new ToolSurface(selected, included, excluded, bounded, socketProfile);
    }

    Map<String, ToolSpec> tools() {
        return tools;
    }

    ToolSpec require(String name) {
        ToolSpec tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("tool '" + name + "' is not enabled on this server");
        }
        return tool;
    }

    Set<ToolSpec.Toolset> toolsets() {
        return toolsets;
    }

    Set<String> inclusions() {
        return inclusions;
    }

    Set<String> exclusions() {
        return exclusions;
    }

    /** Startup-frozen socket path, when this surface was built by the MCP launcher. */
    Optional<String> resolvedSocketPath() {
        return socketProfile == null ? Optional.empty() : Optional.of(socketProfile.resolvedSocketPath());
    }

    List<String> toolsetNames() {
        return toolsets.stream().map(ToolSpec.Toolset::wireName).toList();
    }

    /** Static disclosure captured while the MCP server is built. */
    Map<String, Object> capabilities(Server server) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("frozen", true);
        report.put(
                "boundary",
                Map.of(
                        "oneSocketPerProcess", true,
                        "perCallSocketSelection", false,
                        "hostCommandExecution", false,
                        "dynamicResources", false));
        Map<String, Object> socket = socketReport(server);
        report.put("socket", socket);
        report.put("connection", connectionReport(server, socket));
        report.put("toolsets", toolsetNames());
        report.put("includedTools", List.copyOf(inclusions));
        report.put("excludedTools", List.copyOf(exclusions));
        report.put(
                "selection",
                Map.of(
                        "toolsets", toolsetNames(),
                        "includedTools", List.copyOf(inclusions),
                        "excludedTools", List.copyOf(exclusions)));
        report.put("toolCount", tools.size());
        report.put("effectiveTools", List.copyOf(tools.keySet()));
        report.put("tools", tools.values().stream().map(ToolSurface::capability).toList());
        report.put("hostCommandTools", 0);
        report.put("toolFilteringBoundary", "interface-shaping-not-authorization");
        report.put("executionAuthority", "tmux-user");
        report.put("operatingSystemBoundary", "none");
        return Collections.unmodifiableMap(report);
    }

    private static Map<String, Object> capability(ToolSpec tool) {
        return tool.capability();
    }

    static Map<String, Object> socket(Server server) {
        ServerEndpoint endpoint = server.config().endpoint();
        String selection;
        String selector;
        if (endpoint instanceof ServerEndpoint.Default) {
            selection = "inherited";
            selector = "inherit";
        } else if (endpoint instanceof ServerEndpoint.NamedSocket named) {
            selection = "operator-current";
            selector = "name:" + named.name();
        } else if (endpoint instanceof ServerEndpoint.SocketPath path) {
            selection = "operator-current";
            selector = "path:" + path.path();
        } else {
            throw new IllegalStateException("unrecognized server endpoint " + endpoint);
        }
        Map<String, Object> socket = new LinkedHashMap<>();
        socket.put("selector", selector);
        socket.put("selectionProvenance", selection);
        socket.put("serverState", server.isAlive() ? "existing" : "unknown");
        socket.put("configurationProvenance", "unknown");
        socket.put("namespaceBoundary", "tmux-objects-only");
        return Collections.unmodifiableMap(socket);
    }

    Map<String, Object> socketReport(Server server) {
        return socketProfile == null ? socket(server) : socketProfile.report();
    }

    Map<String, Object> connectionReport(Server server, Map<String, Object> socket) {
        if (socketProfile != null) {
            return socketProfile.connection();
        }
        ServerEndpoint endpoint = server.config().endpoint();
        String path = endpoint instanceof ServerEndpoint.SocketPath selected
                ? selected.path().toString()
                : "";
        StringBuilder attach = new StringBuilder(shellQuote(server.config().binaryPath())).append(" -N");
        if (!path.isBlank()) {
            attach.append(" -S ").append(shellQuote(path));
        } else if (endpoint instanceof ServerEndpoint.NamedSocket named) {
            attach.append(" -L ").append(shellQuote(named.name()));
        }
        attach.append(" attach");
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("socketSelector", socket.get("selector"));
        connection.put("socketProvenance", socket.get("selectionProvenance"));
        connection.put("resolvedSocketPath", path);
        connection.put("serverState", socket.get("serverState"));
        connection.put("configurationProvenance", socket.get("configurationProvenance"));
        connection.put("attachCommand", attach.toString());
        return Collections.unmodifiableMap(connection);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Set<ToolSpec.Toolset> defaults(@Nullable SocketProfile socketProfile) {
        if (socketProfile == null || !socketProfile.defaultTeardown()) {
            return INHERITED_DEFAULTS;
        }
        return Collections.unmodifiableSet(EnumSet.allOf(ToolSpec.Toolset.class));
    }

    private static Set<ToolSpec.Toolset> parseToolsets(String configured) {
        if (configured.isEmpty()) {
            return EnumSet.noneOf(ToolSpec.Toolset.class);
        }
        Set<ToolSpec.Toolset> selected = EnumSet.noneOf(ToolSpec.Toolset.class);
        for (String name : tokens(configured, TOOLSETS_ENV)) {
            selected.add(ToolSpec.Toolset.ofWireName(name));
        }
        return selected;
    }

    private static Set<String> parseToolNames(@Nullable String configured, String variable) {
        if (configured == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(tokens(configured, variable)));
    }

    private static List<String> tokens(String configured, String variable) {
        List<String> names = new ArrayList<>();
        for (String token : configured.split(",", -1)) {
            String name = token.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(variable + " contains an empty name");
            }
            names.add(name);
        }
        return names;
    }

    private static Map<String, ToolSpec> catalogByName() {
        Map<String, ToolSpec> all = new LinkedHashMap<>();
        for (ToolSpec tool : Catalog.tools()) {
            if (all.put(tool.name(), tool) != null) {
                throw new IllegalStateException("duplicate catalog tool '" + tool.name() + "'");
            }
        }
        return all;
    }

    private static void rejectUnknownTools(Set<String> selected, Map<String, ToolSpec> all, String variable) {
        Set<String> unknown = new LinkedHashSet<>(selected);
        unknown.removeAll(all.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    variable + " contains unknown tool(s) " + unknown + "; valid tools: " + all.keySet());
        }
    }
}
