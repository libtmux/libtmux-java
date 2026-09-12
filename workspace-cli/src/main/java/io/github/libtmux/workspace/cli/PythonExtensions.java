package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.snapshot.ServerSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class PythonExtensions {
    private PythonExtensions() {}

    private static final String BUILD = """
            import importlib, json, sys
            from pathlib import Path
            from libtmux import Server
            from tmuxp.workspace import loader
            from tmuxp.workspace.builder import prepended_sys_path, resolve_builder_class, resolve_builder_paths
            request = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
            config = loader.trickle(loader.expand(request['config'], cwd=str(Path(request['source']).parent)))
            config['session_name'] = request['session_name']
            server = Server(**request['server'])
            if request.get('daemon') and server.cmd('display-message', '-p', '#{pid}:#{start_time}').stdout != [request['daemon']]:
                raise RuntimeError('authenticated append daemon changed')
            with prepended_sys_path(resolve_builder_paths(config, request['source'])):
                plugins = []
                for target in config.get('plugins') or []:
                    module, _, name = target.rpartition('.')
                    plugins.append(getattr(importlib.import_module(module), name)())
                builder = resolve_builder_class(config)(session_config=config, server=server, plugins=plugins)
                session = server.sessions.get(session_id=request['append']) if request.get('append') else None
                if request.get('append') and session is None:
                    raise RuntimeError('authenticated append session disappeared')
                try:
                    builder.build(session=session, append=session is not None)
                    for plugin in builder.plugins:
                        plugin.before_script(builder.session)
                finally:
                    try:
                        session_id = builder.session.id
                    except Exception:
                        session_id = request.get('append', '')
                    try:
                        Path(request['state']).write_text(json.dumps({'session_id': session_id}), encoding='utf-8')
                    except OSError as error:
                        print('extension observation failed: ' + str(error), file=sys.stderr)
            """;

    static boolean required(Main.Context context, Path source, ObjectNode config) {
        JsonNode plugins = config.path("plugins");
        if (!plugins.isMissingNode() && !plugins.isNull()) {
            if (!plugins.isArray()) throw Main.usage("plugins must be an array of dotted Python class names");
            for (JsonNode plugin : plugins)
                if (!plugin.isTextual() || !plugin.asText().matches("[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+"))
                    throw Main.usage("plugins must contain dotted Python class names");
        }
        JsonNode builder = config.path("workspace_builder");
        if (!builder.isMissingNode() && !builder.isNull() && !builder.isTextual())
            throw Main.usage("workspace_builder must name a Python builder");
        JsonNode paths = config.path("workspace_builder_paths");
        if (!paths.isMissingNode() && !paths.isNull()) {
            if (paths.isTextual()) paths = Documents.JSON.createArrayNode().add(paths.asText());
            if (!paths.isArray()) throw Main.usage("workspace_builder_paths must contain directory paths");
            for (JsonNode path : paths) {
                if (!path.isTextual() || path.asText().indexOf('\0') >= 0)
                    throw Main.usage("workspace_builder_paths must contain directory paths");
                Path expanded = source.resolveSibling(Catalog.expand(context, path.asText()))
                        .normalize();
                if (!Files.isDirectory(expanded))
                    throw Main.usage("workspace_builder_paths must name existing directories");
            }
        }
        return (plugins.isArray() && !plugins.isEmpty())
                || (builder.isTextual() && !builder.asText().isBlank())
                || (paths.isArray() && !paths.isEmpty());
    }

    static Session build(
            Main.Context context,
            Server server,
            WorkspacePlan plan,
            Optional<Session> borrowed,
            String python,
            Reporter report,
            ObjectNode effects)
            throws IOException, InterruptedException {
        Set<String> windows = new HashSet<>();
        Set<String> panes = new HashSet<>();
        ServerSnapshot before = server.snapshot();
        for (var window : before.windows())
            windows.add(window.context().window().value());
        for (var pane : before.panes()) panes.add(pane.id().value());
        if (borrowed.isPresent()) {
            effects.put("session_id", borrowed.orElseThrow().id().value());
        }
        ObjectNode selection = Documents.JSON
                .createObjectNode()
                .put("tmux_bin", server.config().binary());
        if (server.config().endpoint() instanceof ServerEndpoint.SocketPath socket)
            selection.put("socket_path", socket.path().toString());
        else if (server.config().endpoint() instanceof ServerEndpoint.NamedSocket socket)
            selection.put("socket_name", socket.name());
        server.config().configFile().ifPresent(path -> selection.put("config_file", path.toString()));
        if (server.config().force256Colors()) selection.put("colors", 256);
        String daemon = before.serverPid().isPresent() ? server.expand("#{pid}:#{start_time}") : "";
        if (!daemon.isEmpty() && !daemon.startsWith(before.serverPid().orElseThrow() + ":"))
            throw Main.usage("selected tmux daemon changed before Python extension execution");
        Path scratch = Files.createTempDirectory("tmux-workspace-extension-");
        Path request = scratch.resolve("request.json");
        Path state = scratch.resolve("state.json");
        effects.put("engine", "python").put("effects_scope", "observed").put("stage", "python_extension");
        String sessionId = borrowed.map(session -> session.id().value()).orElse("");
        try {
            ObjectNode payload = Documents.JSON
                    .createObjectNode()
                    .put("source", plan.source().toString())
                    .put("session_name", plan.name())
                    .put("append", sessionId)
                    .put("daemon", daemon)
                    .put("state", state.toString());
            payload.set("config", plan.extension());
            payload.set("server", selection);
            Files.writeString(request, payload.toString());
            report.event(
                    "warning",
                    Documents.JSON
                            .createObjectNode()
                            .put("code", "python_extension_bridge")
                            .put(
                                    "message",
                                    "Python extensions run through tmuxp 1.74.0; reported effects are observed topology"));
            effects.put("effects_unknown", true).put("changed", true);
            try {
                Children.Output output = Children.run(
                        context,
                        List.of(python, "-u", "-c", BUILD, request.toString()),
                        context.directory(),
                        report,
                        Duration.ofHours(24));
                effects.set("script_output", output.value());
                if (output.status() != 0)
                    throw new Main.Failure(
                            "python_extension_failed", 1, "Python extension exited with " + output.status());
            } finally {
                try {
                    if (borrowed.isEmpty() && Files.exists(state) && Files.size(state) != 0)
                        sessionId = Documents.JSON
                                .readTree(state.toFile())
                                .path("session_id")
                                .asText();
                } catch (IOException | RuntimeException unavailable) {
                    effects.put("observation_error", String.valueOf(unavailable.getMessage()));
                }
                observe(server, plan, sessionId, daemon, windows, panes, effects);
            }
            String observed = effects.path("session_id").asText();
            return server.sessions().stream()
                    .filter(session -> session.id().value().equals(observed))
                    .findFirst()
                    .orElseThrow(() ->
                            new Main.Failure("python_extension_failed", 1, "Python extension left no target session"));
        } finally {
            for (Path file : List.of(state, request, scratch)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException failure) {
                    effects.put("cleanup_error", "could not remove private extension temporary files");
                    Main.diagnostic(
                            context,
                            report.machine(),
                            "extension_cleanup",
                            "could not remove private extension temporary files");
                }
            }
        }
    }

    private static void observe(
            Server server,
            WorkspacePlan plan,
            String id,
            String daemon,
            Set<String> beforeWindows,
            Set<String> beforePanes,
            ObjectNode effects) {
        boolean interrupted = Thread.interrupted();
        try (Server observer = Server.open(server.config().toBuilder()
                .defaultTimeout(Duration.ofMillis(250))
                .build())) {
            if (!daemon.isEmpty() && !observer.expand("#{pid}:#{start_time}").equals(daemon)) {
                effects.put("observation_error", "authenticated append daemon changed");
                return;
            }
            ServerSnapshot snapshot = observer.snapshot();
            var target = snapshot.sessions().stream()
                    .filter(session -> id.isEmpty()
                            ? session.name().equals(plan.name())
                            : session.id().value().equals(id))
                    .findFirst();
            effects.put("target_present", target.isPresent());
            if (target.isEmpty()) return;
            var session = target.orElseThrow();
            effects.put("session_id", session.id().value()).put("session_name", session.name());
            for (var window : snapshot.windows())
                if (window.context().session().equals(session.id())
                        && !beforeWindows.contains(window.context().window().value()))
                    effects.withArray("window_ids")
                            .add(window.context().window().value());
            for (var pane : snapshot.panes())
                if (pane.context().session().equals(session.id())
                        && !beforePanes.contains(pane.id().value()))
                    effects.withArray("pane_ids").add(pane.id().value());
        } catch (RuntimeException unavailable) {
            effects.put("observation_error", String.valueOf(unavailable.getMessage()));
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
