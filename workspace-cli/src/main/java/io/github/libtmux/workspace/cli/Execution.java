package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.Layout;
import io.github.libtmux.Options;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.SplitSpec;
import io.github.libtmux.Window;
import io.github.libtmux.WindowSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine.ParseResult;

final class Execution {
    private Execution() {}

    static Server server(Main.Context context, ParseResult args) {
        var server = Server.builder()
                .binary(Children.executable(context, context.environment().getOrDefault("LIBTMUX_TEST_TMUX", "tmux")));
        String socket = args.matchedOptionValue("-S", "");
        String name = args.matchedOptionValue("-L", "");
        if (!socket.isEmpty())
            server.endpoint(ServerEndpoint.socketPath(context.directory().resolve(Catalog.expand(context, socket))));
        else if (!name.isEmpty()) server.endpoint(ServerEndpoint.namedSocket(name));
        else
            io.github.libtmux.TmuxEnvironment.of(context.environment())
                    .ifPresent(inherited -> server.endpoint(ServerEndpoint.socketPath(inherited.socket())));
        String config = args.matchedOptionValue("-f", "");
        if (args.commandSpec().name().equals("load") && !config.isEmpty())
            server.configFile(context.directory().resolve(config));
        return server.build();
    }

    static void load(Main.Context context, ParseResult args, Reporter report) throws IOException, InterruptedException {
        boolean append = Main.flag(args, "--append");
        boolean detached = Main.flag(args, "-d");
        if (report.machine() && !detached && !append) throw Main.usage("machine load requires -d or --append");
        if (!detached && !append && !Main.controllingTerminal())
            throw Main.usage("attached load requires a terminal; pass -d");
        if (append && !context.environment().containsKey("TMUX_PANE"))
            throw Main.usage("append requires a resolved current TMUX_PANE");
        String[] sources = args.matchedPositionalValue(0, new String[0]);
        List<WorkspacePlan> plans = new ArrayList<>();
        for (int index = 0; index < sources.length; index++) {
            Path source = Catalog.resolve(context, sources[index], "load");
            plans.add(WorkspacePlan.read(
                    context, source, index == sources.length - 1 ? args.matchedOptionValue("-s", "") : ""));
        }
        try (Server server = server(context, args)) {
            if (append) authenticate(context, server);
            Optional<io.github.libtmux.Client> invoking =
                    detached || append ? Optional.empty() : invokingClient(context, server);
            ArrayNode results = Documents.JSON.createArrayNode();
            report.event("started", Documents.JSON.createObjectNode().put("inputs", plans.size()));
            Session last = null;
            for (int index = 0; index < plans.size(); index++) {
                WorkspacePlan plan = plans.get(index);
                ObjectNode effects = Documents.JSON
                        .createObjectNode()
                        .put("input_index", index)
                        .put("input", Catalog.mask(context, plan.source()))
                        .put("owned_session", false)
                        .put("stage", "resolve");
                effects.putArray("window_ids");
                effects.putArray("pane_ids");
                report.event("workspace-started", effects.deepCopy());
                try {
                    last = build(context, server, plan, append, report, effects);
                    results.add(effects);
                    report.event("workspace-completed", effects.deepCopy());
                } catch (RuntimeException | IOException | InterruptedException failure) {
                    boolean partial = effects.path("changed").asBoolean() || !results.isEmpty();
                    ObjectNode error = Documents.JSON
                            .createObjectNode()
                            .put("code", failure instanceof InterruptedException ? "interrupted" : "load_failed")
                            .put("message", String.valueOf(failure.getMessage()))
                            .put("input_index", index)
                            .put("partial_effects", partial);
                    error.set("effects", effects);
                    ObjectNode summary = summary(partial ? "partial" : "error", results);
                    summary.withArray("errors").add(error);
                    if (report.streaming()) report.event("failed", summary);
                    else if (report.machine()) report.document(summary);
                    throw failure;
                }
            }
            ObjectNode summary = summary("ok", results);
            if (report.streaming()) report.event("completed", summary);
            else if (report.machine()) report.document(summary);
            else report.line("success", "Loaded", results.size() + " workspaces");
            if (!detached && !append && last != null) attach(server, last, context, invoking);
        }
    }

    private static ObjectNode summary(String status, ArrayNode results) {
        ObjectNode value = Documents.JSON
                .createObjectNode()
                .put("schema_version", 1)
                .put("command", "load")
                .put("status", status);
        value.set("results", results);
        value.putArray("errors");
        return value;
    }

    private static Session build(
            Main.Context context,
            Server server,
            WorkspacePlan plan,
            boolean append,
            Reporter report,
            ObjectNode effects)
            throws IOException, InterruptedException {
        Optional<Session> existing = server.isAlive()
                ? server.sessions().stream()
                        .filter(session -> session.name().equals(plan.name()))
                        .findFirst()
                : Optional.empty();
        if (!append && existing.isPresent()) {
            Session session = existing.orElseThrow();
            effects.put("session_id", session.id().value())
                    .put("session_name", session.name())
                    .put("reused", true)
                    .put("stage", "reused");
            return session;
        }
        Session session;
        Window bootstrap = null;
        if (append) {
            authenticate(context, server);
            String current = context.environment().getOrDefault("TMUX_PANE", "");
            session = server.panes().stream()
                    .filter(pane -> pane.id().value().equals(current))
                    .findFirst()
                    .orElseThrow(() -> Main.usage("TMUX_PANE does not resolve on the selected server"))
                    .window()
                    .session();
        } else {
            session = server.newSession(SessionSpec.builder()
                    .named(plan.name())
                    .in(plan.directory())
                    .build());
            bootstrap = session.windows().getFirst();
            effects.put("owned_session", true).put("changed", true);
        }
        effects.put("session_id", session.id().value())
                .put("session_name", session.name())
                .put("reused", false);
        if (append) {
            effects.put("stage", "windows_preflight");
            Set<Integer> occupied = new HashSet<>();
            for (Window window : session.windows()) occupied.add(window.index().value());
            reserveIndexes(plan, occupied);
        }
        if (!append) report.event("session-created", effects.deepCopy());
        effects.put("stage", "before_script");
        if (!plan.beforeScript().isEmpty()) {
            Children.Output output =
                    Children.run(context, plan.beforeScript(), plan.scriptDirectory(), report, Duration.ofHours(24));
            effects.set("script_output", output.value());
            if (output.status() != 0)
                throw new Main.Failure("before_script_failed", 1, "before_script exited with " + output.status());
        }
        effects.put("stage", "options");
        apply(session.options(), plan.options(), effects);
        apply(server.globalOptions(), plan.globalOptions(), effects);
        for (var variable : plan.environment().entrySet()) {
            server.run(List.of("set-environment", "-t", session.id().value(), variable.getKey(), variable.getValue()));
            effects.put("changed", true);
        }
        if (!plan.options().isEmpty()
                || !plan.globalOptions().isEmpty()
                || !plan.environment().isEmpty()) effects.put("changed", true);
        boolean readiness = plan.readiness() != WorkspacePlan.Readiness.NEVER
                && plan.windows().stream()
                        .flatMap(window -> window.panes().stream())
                        .anyMatch(pane ->
                                pane.shell().isEmpty() && !pane.commands().isEmpty());
        if (readiness && plan.readiness() == WorkspacePlan.Readiness.AUTO) {
            effects.put("stage", "readiness");
            String shell = session.options()
                    .get("default-shell")
                    .orElse(context.environment().getOrDefault("SHELL", ""));
            readiness = shell.equals("zsh") || shell.endsWith("/zsh");
        }
        Set<Integer> occupied = new HashSet<>();
        Session current = plan.beforeScript().isEmpty() ? session : session.refresh();
        for (Window window : current.windows())
            if (bootstrap == null || !window.id().equals(bootstrap.id()))
                occupied.add(window.index().value());
        reserveIndexes(plan, occupied);
        int next = plan.windows().stream().anyMatch(window -> window.index() < 0)
                ? Integer.parseInt(session.options().get("base-index").orElse("0"))
                : 0;
        List<Integer> indexes = new ArrayList<>();
        for (WorkspacePlan.Window window : plan.windows()) {
            if (window.index() >= 0) indexes.add(window.index());
            else {
                next = freeIndex(occupied, next);
                indexes.add(next);
                occupied.add(next);
            }
        }
        if (bootstrap != null) {
            int temporary = freeIndex(occupied, 0);
            if (bootstrap.index().value() != temporary) bootstrap.moveTo(session, temporary);
        }
        Window focused = null;
        int ordinal = 0;
        for (WorkspacePlan.Window spec : plan.windows()) {
            effects.put("stage", "windows");
            WorkspacePlan.Pane first = spec.panes().getFirst();
            var create = WindowSpec.builder().detached().in(first.directory()).environment(first.environment());
            if (!spec.name().isEmpty()) create.named(spec.name());
            create.atIndex(indexes.get(ordinal++));
            if (!first.shell().isEmpty()) create.running(first.shell());
            Window window = session.newWindow(create.build());
            effects.put("changed", true);
            effects.withArray("window_ids").add(window.id().value());
            report.event(
                    "window-created",
                    Documents.JSON
                            .createObjectNode()
                            .put("window_id", window.id().value())
                            .put("window_index", window.index().value())
                            .put("window_name", window.name()));
            List<Pane> panes = new ArrayList<>();
            panes.add(window.panes().getFirst());
            created(panes.getFirst(), window, report, effects);
            apply(window.options(), spec.options(), effects);
            for (int index = 1; index < spec.panes().size(); index++) {
                WorkspacePlan.Pane pane = spec.panes().get(index);
                var split = SplitSpec.builder()
                        .below()
                        .detached()
                        .in(pane.directory())
                        .environment(pane.environment());
                if (!pane.shell().isEmpty()) split.running(pane.shell());
                panes.add(panes.getLast().split(split.build()));
                created(panes.getLast(), window, report, effects);
                window.selectLayout(Layout.TILED);
            }
            if (!spec.layout().isEmpty()) {
                Optional<Layout> layout = java.util.Arrays.stream(Layout.values())
                        .filter(value -> value.tmuxName().equals(spec.layout()))
                        .findFirst();
                if (layout.isPresent()) window.selectLayout(layout.orElseThrow());
                else window.applyLayout(spec.layout());
            }
            for (int index = 0; index < panes.size(); index++) {
                Pane pane = panes.get(index);
                WorkspacePlan.Pane config = spec.panes().get(index);
                if (readiness && config.shell().isEmpty() && !config.commands().isEmpty()) {
                    effects.put("stage", "readiness");
                    if (!ready(pane))
                        report.event(
                                "warning",
                                Documents.JSON
                                        .createObjectNode()
                                        .put("code", "pane_readiness_timeout")
                                        .put(
                                                "message",
                                                "pane cursor stayed at origin for 2 seconds; sending configured commands")
                                        .put("pane_id", pane.id().value()));
                }
                effects.put("stage", "commands");
                for (WorkspacePlan.Command command : config.commands()) {
                    Thread.sleep(command.before());
                    pane.sendLiteral(List.of(command.text()));
                    if (command.enter()) pane.sendKeys(List.of("Enter"));
                    Thread.sleep(command.after());
                }
                if (config.focus()) pane.select();
            }
            apply(window.options(), spec.optionsAfter(), effects);
            if (focused == null || spec.focus()) focused = window;
        }
        effects.put("stage", "finalize");
        if (bootstrap != null) removeBootstrap(session, bootstrap, effects);
        if (focused != null) focused.select();
        effects.put("stage", "completed");
        return session.refresh();
    }

    private static void removeBootstrap(Session session, Window bootstrap, ObjectNode effects) {
        Options options = session.options();
        boolean renumber = options.get("renumber-windows").orElse("off").equals("on");
        String previous = renumber ? options.all().get("renumber-windows") : null;
        RuntimeException failed = null;
        try {
            if (renumber) options.set("renumber-windows", "off");
            bootstrap.kill();
        } catch (RuntimeException failure) {
            failed = failure;
        } finally {
            if (renumber) {
                try {
                    if (previous == null) options.unset("renumber-windows");
                    else options.set("renumber-windows", previous);
                } catch (RuntimeException restoreFailure) {
                    effects.put("renumber_restore_error", String.valueOf(restoreFailure.getMessage()));
                    if (failed == null) failed = restoreFailure;
                    else failed.addSuppressed(restoreFailure);
                }
            }
        }
        if (failed != null) throw failed;
    }

    private static void reserveIndexes(WorkspacePlan plan, Set<Integer> occupied) {
        for (WorkspacePlan.Window window : plan.windows())
            if (window.index() >= 0 && !occupied.add(window.index()))
                throw Main.usage("window_index " + window.index() + " already exists in the selected session");
    }

    private static int freeIndex(Set<Integer> occupied, int first) {
        int index = first;
        while (occupied.contains(index)) {
            if (index == Integer.MAX_VALUE) throw Main.usage("no free window index at or above " + first);
            index++;
        }
        return index;
    }

    private static boolean ready(Pane pane) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        do {
            String cursor = pane.expand("#{cursor_x}:#{cursor_y}");
            if (!cursor.isEmpty() && !cursor.equals("0:0")) return true;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static void apply(Options target, Map<String, String> values, ObjectNode effects) {
        values.forEach((key, value) -> {
            target.set(key, value);
            effects.put("changed", true);
        });
    }

    private static void created(Pane pane, Window window, Reporter report, ObjectNode effects) throws IOException {
        effects.withArray("pane_ids").add(pane.id().value());
        report.event(
                "pane-created",
                Documents.JSON
                        .createObjectNode()
                        .put("pane_id", pane.id().value())
                        .put("window_id", window.id().value()));
    }

    private static void authenticate(Main.Context context, Server selected) {
        if (!sameDaemon(context, selected))
            throw Main.usage("selected server does not match the inherited tmux daemon");
    }

    private static boolean sameDaemon(Main.Context context, Server selected) {
        var inherited = io.github.libtmux.TmuxEnvironment.of(context.environment())
                .orElseThrow(() -> Main.usage("append or switch requires valid inherited TMUX context"));
        try (Server original = Server.open(selected.config().toBuilder()
                .endpoint(ServerEndpoint.socketPath(inherited.socket()))
                .build())) {
            String identity = original.expand("#{pid}:#{start_time}");
            if (!identity.startsWith(inherited.serverPid() + ":"))
                throw Main.usage("inherited tmux daemon identity is stale");
            return selected.isAlive() && identity.equals(selected.expand("#{pid}:#{start_time}"));
        }
    }

    private static Optional<io.github.libtmux.Client> invokingClient(Main.Context context, Server selected) {
        if (!context.environment().containsKey("TMUX") || !sameDaemon(context, selected)) return Optional.empty();
        String pane = context.environment().getOrDefault("TMUX_PANE", "");
        var clients = selected.clients().stream()
                .filter(client -> client.attachment().isPresent()
                        && client.attachment()
                                .orElseThrow()
                                .activePane()
                                .id()
                                .value()
                                .equals(pane))
                .toList();
        if (clients.size() != 1) throw Main.usage("cannot identify one invoking tmux client; load detached with -d");
        return Optional.of(clients.getFirst());
    }

    private static void attach(
            Server server, Session session, Main.Context context, Optional<io.github.libtmux.Client> invoking)
            throws IOException, InterruptedException {
        if (invoking.isPresent()) {
            authenticate(context, server);
            var client = invoking.orElseThrow();
            String pane = context.environment().getOrDefault("TMUX_PANE", "");
            var attachment = client.fetchAttachment();
            if (attachment.isEmpty()
                    || !attachment.orElseThrow().activePane().id().value().equals(pane)) {
                throw Main.usage("invoking tmux client changed during load; workspace remains loaded");
            }
            client.switchTo(session);
            return;
        }
        var argv = new ArrayList<>(server.config().endpointCommand());
        argv.add("attach-session");
        argv.add("-t");
        argv.add(session.id().value());
        java.io.File tty = Children.terminalDevice(context);
        ProcessBuilder builder =
                new ProcessBuilder(argv).redirectInput(tty).redirectOutput(tty).redirectError(tty);
        builder.environment().clear();
        builder.environment().putAll(context.environment());
        builder.environment().remove("TMUX");
        builder.environment().remove("TMUX_PANE");
        Process child = builder.start();
        try {
            int status = child.waitFor();
            if (status != 0) throw new Main.Failure("attach_failed", status, "tmux attachment failed");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    static void freeze(Main.Context context, ParseResult args, Reporter report) throws IOException {
        try (Server server = server(context, args)) {
            String name = args.matchedPositionalValue(0, "");
            List<Session> sessions = server.sessions();
            Session session = name.isEmpty() && sessions.size() == 1
                    ? sessions.getFirst()
                    : sessions.stream()
                            .filter(value -> value.name().equals(name))
                            .findFirst()
                            .orElseThrow(() -> Main.usage("select a live session by name"));
            ObjectNode captured = Documents.JSON.createObjectNode().put("session_name", session.name());
            captured.set("options", Documents.JSON.valueToTree(session.options().all()));
            ArrayNode windows = captured.putArray("windows");
            for (Window window : session.windows()) {
                ObjectNode node = windows.addObject()
                        .put("window_name", window.name())
                        .put("window_index", window.index().value())
                        .put("layout", window.layout())
                        .put("focus", window.active());
                node.set(
                        "options_after",
                        Documents.JSON.valueToTree(window.options().all()));
                ArrayNode panes = node.putArray("panes");
                for (Pane pane : window.panes())
                    panes.addObject()
                            .put("start_directory", pane.currentPath().toString())
                            .put("focus", pane.active())
                            .putArray("shell_command");
            }
            String format = args.matchedOptionValue("--workspace-format", "yaml");
            String destination = args.matchedOptionValue("--save-to", "");
            ObjectNode result = Documents.JSON
                    .createObjectNode()
                    .put("schema_version", 1)
                    .put("command", "freeze")
                    .put("status", "ok");
            result.putArray("warnings")
                    .add(
                            "Capture preserves topology and directories; original commands, scripts and plugin intent cannot be recovered.");
            if (destination.isEmpty() && report.machine()) {
                if (report.streaming()) {
                    result.set("workspace", captured);
                    report.event("completed", result);
                } else report.document(captured);
            } else {
                if (destination.isEmpty()) destination = session.name() + "." + format;
                Path path = context.directory().resolve(Catalog.expand(context, destination));
                if (!report.machine() && !Main.flag(args, "--yes"))
                    Documents.confirm(context, "Save " + Catalog.mask(context, path) + "?");
                Documents.write(path, captured, format, Main.flag(args, "--force"));
                result.put("destination", Catalog.mask(context, path)).put("format", format);
                if (report.streaming()) report.event("completed", result);
                else if (report.machine()) report.document(result);
                else if (!Main.flag(args, "--quiet")) report.line("success", "Saved", Catalog.mask(context, path));
            }
        }
    }
}
