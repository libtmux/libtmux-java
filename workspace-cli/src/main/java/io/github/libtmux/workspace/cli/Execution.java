package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.libtmux.Dimensions;
import io.github.libtmux.Layout;
import io.github.libtmux.Layouts;
import io.github.libtmux.Options;
import io.github.libtmux.Pane;
import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.SplitSpec;
import io.github.libtmux.UnsupportedTmuxVersion;
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
        var server = Server.builder().force256Colors(Main.flag(args, "-2")).binary(tmuxExecutable(context));
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

    /** A missing tmux is `tmux_unavailable`, not the raw {@code executable_not_found} of the lookup. */
    private static String tmuxExecutable(Main.Context context) {
        try {
            return Children.executable(context, context.environment().getOrDefault("LIBTMUX_TEST_TMUX", "tmux"));
        } catch (Main.Failure absent) {
            if (!absent.code.equals("executable_not_found")) throw absent;
            throw new Main.Failure(
                    "tmux_unavailable", 1, java.util.Objects.toString(absent.getMessage(), "tmux is not available"));
        }
    }

    static void load(Main.Context context, ParseResult args, Reporter report) throws IOException, InterruptedException {
        if (Main.flag(args, "-8"))
            throw new Main.Failure(
                    "unsupported_color_mode",
                    2,
                    "tmux 3.2a and newer do not support legacy 88-color mode (-8); use -2 or terminal detection");
        boolean detached = Main.flag(args, "-d");
        // -d beats --append: a detached load never has a current session to append into.
        boolean append = Main.flag(args, "--append") && !detached;
        boolean yes = Main.flag(args, "--yes");
        if (report.machine() && !detached && !append) throw Main.usage("machine load requires -d or --append");
        if (append && !context.environment().containsKey("TMUX_PANE"))
            throw Main.usage("append requires a resolved current TMUX_PANE");
        String[] sources = args.matchedPositionalValue(0, new String[0]);
        List<WorkspacePlan> plans = new ArrayList<>();
        for (int index = 0; index < sources.length; index++) {
            Path source = Catalog.resolve(context, sources[index], "load");
            WorkspacePlan plan = WorkspacePlan.read(
                    context, source, index == sources.length - 1 ? args.matchedOptionValue("-s", "") : "");
            for (String warning : plan.warnings())
                report.event(
                        "warning",
                        Documents.JSON
                                .createObjectNode()
                                .put("code", "start_directory_missing")
                                .put("message", warning));
            plans.add(plan);
        }
        requireAppendExtensionCompatibility(plans, append);
        String python =
                plans.stream().anyMatch(plan -> plan.extension() != null) ? Children.python(context, report) : "";
        try (Server server = server(context, args)) {
            for (WorkspacePlan plan : plans) {
                for (WorkspacePlan.Window window : plan.windows()) {
                    if (!window.layout().isEmpty())
                        Layouts.require(window.layout(), server, window.panes().size());
                }
            }
            // Resolved before anything is built: refuses a different server outright, and a prompt
            // here can still turn this load into a detached or an appended one.
            Optional<AttachTarget> target =
                    detached || append ? Optional.empty() : Optional.of(attachTarget(context, server));
            if (target.isPresent() && !report.machine() && !yes && Main.terminal()) {
                AttachTarget resolved = target.orElseThrow();
                boolean exists = server.isAlive()
                        && server.sessions().stream()
                                .anyMatch(session ->
                                        session.name().equals(plans.getLast().name()));
                if (exists) {
                    if (promptAnswer(context, plans.getLast().name() + " is already running. Attach? [Y/n] ", 'y')
                            == 'n') return;
                } else if (resolved.insideTmux()) {
                    char answer = promptAnswer(
                            context,
                            "Already inside tmux: switch (y), load detached (n), or append (a)? [y/n/a] ",
                            'y');
                    if (answer == 'n') detached = true;
                    else if (answer == 'a') {
                        append = true;
                        // The eager check above ran before the prompt could set this.
                        requireAppendExtensionCompatibility(plans, true);
                    }
                }
            }
            if (target.isPresent() && !target.orElseThrow().insideTmux() && !detached && !Main.controllingTerminal())
                throw Main.usage("attached load requires a terminal; pass -d");
            Optional<Session> borrowed = append ? Optional.of(appendTarget(context, server)) : Optional.empty();
            ArrayNode results = Documents.JSON.createArrayNode();
            report.event("started", Documents.JSON.createObjectNode().put("inputs", plans.size()));
            Session last = null;
            for (int index = 0; index < plans.size(); index++) {
                WorkspacePlan plan = plans.get(index);
                ObjectNode effects = Documents.JSON
                        .createObjectNode()
                        .put("input_index", index)
                        .put("input", Catalog.mask(context, plan.source()))
                        .put("session_name", borrowed.map(Session::name).orElse(plan.name()))
                        .put("window_total", plan.windows().size())
                        .put(
                                "session_pane_total",
                                plan.windows().stream()
                                        .mapToInt(window -> window.panes().size())
                                        .sum())
                        .put("owned_session", false)
                        .put("stage", "resolve");
                effects.putArray("window_ids");
                effects.putArray("pane_ids");
                report.event("workspace-started", effects.deepCopy());
                try {
                    last = build(
                            context, server, plans.subList(index, plans.size()), borrowed, python, report, effects);
                    results.add(effects);
                    report.event("workspace-completed", effects.deepCopy());
                } catch (RuntimeException | IOException | InterruptedException failure) {
                    boolean partial = effects.path("changed").asBoolean() || !results.isEmpty();
                    ObjectNode error = Documents.JSON
                            .createObjectNode()
                            .put("code", Main.failureCode(failure, "load_failed"))
                            .put("message", String.valueOf(failure.getMessage()))
                            .put("input_index", index)
                            .put("partial_effects", partial);
                    error.set("effects", effects);
                    results.add(effects.deepCopy());
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
            else
                for (JsonNode effects : results)
                    report.line(
                            "success",
                            append
                                    ? "Appended"
                                    : effects.path("reused").asBoolean() ? "Reused session" : "Created session",
                            effects.path("session_name").asText());
            if (!detached && !append && last != null) attach(server, last, context, target.orElseThrow());
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
            List<WorkspacePlan> pending,
            Optional<Session> borrowed,
            String python,
            Reporter report,
            ObjectNode effects)
            throws IOException, InterruptedException {
        WorkspacePlan plan = pending.getFirst();
        boolean append = borrowed.isPresent();
        List<WorkspacePlan> reservations = append ? pending : List.of(plan);
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
        if (plan.extension() != null) {
            if (append) {
                authenticate(context, server);
                borrowed = Optional.of(borrowed.orElseThrow().refresh());
            }
            return PythonExtensions.build(context, server, plan, borrowed, python, report, effects);
        }
        Session session;
        Window bootstrap = null;
        if (append) {
            authenticate(context, server);
            session = borrowed.orElseThrow().refresh();
        } else {
            session = newOwnedSession(context, server, plan);
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
            for (WorkspacePlan reserved : reservations) reserveIndexes(reserved, occupied);
        }
        if (!append) report.event("session-created", effects.deepCopy());
        // Listed only after the event: tmux insists on a first window, and this one is killed as
        // soon as the workspace has a window of its own. It is reported while it can still survive.
        if (bootstrap != null) {
            effects.withArray("window_ids").add(bootstrap.id().value());
            for (Pane pane : bootstrap.panes())
                effects.withArray("pane_ids").add(pane.id().value());
        }
        effects.put("stage", "before_script");
        if (!plan.beforeScript().isEmpty()) {
            Children.Output output = Children.script(
                    context,
                    plan.beforeScript(),
                    plan.scriptDirectory(),
                    report,
                    Duration.ofHours(24),
                    effects.path("input_index").asInt());
            effects.set("script_output", output.value());
            if (output.status() != 0) {
                // The session this load created must not outlive its own failed setup; a
                // borrowed or appended one is never this load's to remove.
                if (bootstrap != null) {
                    session.kill();
                    effects.put("changed", false).put("owned_session", false).put("session_removed", true);
                }
                throw new Main.Failure("script_failed", 1, "before_script exited with " + output.status());
            }
        }
        effects.put("stage", "options");
        apply(session.options(), plan.options(), effects);
        apply(server.globalOptions(), plan.globalOptions(), effects);
        for (var variable : plan.environment().entrySet()) {
            server.run(List.of("set-environment", "-t", session.id().value(), variable.getKey(), variable.getValue()));
            effects.put("changed", true);
        }
        boolean readiness = plan.readiness() != WorkspacePlan.Readiness.NEVER
                && plan.windows().stream()
                        .flatMap(window -> window.panes().stream())
                        .anyMatch(pane ->
                                pane.shell().isEmpty() && !pane.commands().isEmpty());
        if (readiness && plan.readiness() == WorkspacePlan.Readiness.AUTO) {
            effects.put("stage", "readiness");
            String shell = session.options().get("default-shell").orElse("");
            readiness = shell.equals("zsh") || shell.endsWith("/zsh");
        }
        Set<Integer> occupied = new HashSet<>();
        Session current = plan.beforeScript().isEmpty() ? session : session.refresh();
        for (Window window : current.windows())
            if (bootstrap == null || !window.id().equals(bootstrap.id()))
                occupied.add(window.index().value());
        for (WorkspacePlan reserved : reservations) reserveIndexes(reserved, occupied);
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
        int inputIndex = effects.path("input_index").asInt();
        String sessionId = effects.path("session_id").asText();
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
            List<Pane> panes = new ArrayList<>();
            panes.add(window.panes().getFirst());
            effects.withArray("pane_ids").add(panes.getFirst().id().value());
            report.event(
                    "window-created",
                    Documents.JSON
                            .createObjectNode()
                            .put("input_index", inputIndex)
                            .put("session_id", sessionId)
                            .put("window_id", window.id().value())
                            .put("window_index", window.index().value())
                            .put("pane_total", spec.panes().size())
                            .put("window_name", window.name()));
            created(panes.getFirst(), window, inputIndex, sessionId, 0, report);
            if (bootstrap != null) {
                effects.put("stage", "finalize");
                removeBootstrap(session, bootstrap, effects);
                bootstrap = null;
                effects.put("stage", "windows");
            }
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
                effects.withArray("pane_ids").add(panes.getLast().id().value());
                created(panes.getLast(), window, inputIndex, sessionId, index, report);
                window.selectLayout(Layout.TILED);
            }
            if (!spec.layout().isEmpty()) {
                String canonical = Layouts.require(spec.layout(), server, panes.size());
                Optional<Layout> layout = java.util.Arrays.stream(Layout.values())
                        .filter(value -> value.tmuxName().equals(canonical))
                        .findFirst();
                if (layout.isPresent()) window.selectLayout(layout.orElseThrow());
                else window.applyLayout(canonical);
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
                report.event(
                        "pane-completed",
                        Documents.JSON
                                .createObjectNode()
                                .put("input_index", inputIndex)
                                .put("session_id", sessionId)
                                .put("window_id", window.id().value())
                                .put("pane_id", pane.id().value())
                                .put("pane_index", index));
            }
            apply(window.options(), spec.optionsAfter(), effects);
            // A new session defaults to its first window; an appended one leaves the
            // client where it was unless a window explicitly asks for focus.
            if ((!append && focused == null) || spec.focus()) focused = window;
            report.event(
                    "window-completed",
                    Documents.JSON
                            .createObjectNode()
                            .put("input_index", inputIndex)
                            .put("session_id", sessionId)
                            .put("window_id", window.id().value())
                            .put("window_index", window.index().value()));
        }
        effects.put("stage", "finalize");
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
            effects.withArray("window_ids")
                    .removeIf(value -> value.asText().equals(bootstrap.id().value()));
            Set<String> paneIds = new HashSet<>();
            for (Pane pane : bootstrap.panes()) paneIds.add(pane.id().value());
            effects.withArray("pane_ids").removeIf(value -> paneIds.contains(value.asText()));
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

    private static void requireAppendExtensionCompatibility(List<WorkspacePlan> plans, boolean append) {
        for (WorkspacePlan plan : plans)
            if (append && plan.extension() != null && plan.extension().has("before_script"))
                throw new Main.Failure(
                        "unsupported_combination",
                        2,
                        "Python extension append cannot use before_script: tmuxp can delete the borrowed session on failure");
    }

    private static void reserveIndexes(WorkspacePlan plan, Set<Integer> occupied) {
        for (WorkspacePlan.Window window : plan.windows())
            if (window.index() >= 0 && !occupied.add(window.index()))
                throw new Main.Failure("tmux_failed", 1, "create window failed: index " + window.index() + " in use");
    }

    private static int freeIndex(Set<Integer> occupied, int first) {
        int index = first;
        while (occupied.contains(index)) {
            if (index == Integer.MAX_VALUE) throw Main.usage("no free window index at or above " + first);
            index++;
        }
        return index;
    }

    /**
     * A new owned session, sized like tmuxp's terminal-size detection where {@code Server}
     * can honor it, and plain otherwise.
     *
     * <p>{@code TMUXP_DEFAULT_COLUMNS}/{@code TMUXP_DEFAULT_ROWS} (else {@code COLUMNS}/{@code ROWS},
     * else 80x24) seed the size; the real stdout's terminal overrides it where there is one, and
     * {@code COLUMNS}/{@code LINES} override that. {@code TMUXP_DETECT_TERMINAL_SIZE} set to anything
     * but {@code 1} disables detection outright. Below tmux 3.3a, {@code Server} refuses a size; that
     * refusal is caught here rather than predicted, because it also carries the running version.
     */
    private static Session newOwnedSession(Main.Context context, Server server, WorkspacePlan plan)
            throws IOException, InterruptedException {
        SessionSpec.Builder spec = SessionSpec.builder().named(plan.name()).in(plan.directory());
        Optional<Dimensions> size = sessionDimensions(context);
        if (size.isEmpty()) return server.newSession(spec.build());
        try {
            return server.newSession(spec.sized(size.orElseThrow()).build());
        } catch (UnsupportedTmuxVersion tooOld) {
            return server.newSession(SessionSpec.builder()
                    .named(plan.name())
                    .in(plan.directory())
                    .build());
        }
    }

    private static Optional<Dimensions> sessionDimensions(Main.Context context)
            throws IOException, InterruptedException {
        Map<String, String> env = context.environment();
        int width = envInt(env, "TMUXP_DEFAULT_COLUMNS", envInt(env, "COLUMNS", 80));
        int height = envInt(env, "TMUXP_DEFAULT_ROWS", envInt(env, "ROWS", 24));
        String detect = env.get("TMUXP_DETECT_TERMINAL_SIZE");
        if (detect != null && !detect.equals("1")) return Optional.empty();
        Optional<Dimensions> terminal = Children.stdoutSize(context);
        if (terminal.isPresent()) {
            width = terminal.orElseThrow().width();
            height = terminal.orElseThrow().height();
        }
        width = envInt(env, "COLUMNS", width);
        height = envInt(env, "LINES", height);
        return Optional.of(new Dimensions(width, height));
    }

    private static int envInt(Map<String, String> env, String name, int fallback) {
        String raw = env.get(name);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 1 || value > 65535) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw Main.usage(name + " must be 1..65535");
        }
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

    private static void created(
            Pane pane, Window window, int inputIndex, String sessionId, int paneIndex, Reporter report)
            throws IOException {
        report.event(
                "pane-created",
                Documents.JSON
                        .createObjectNode()
                        .put("input_index", inputIndex)
                        .put("session_id", sessionId)
                        .put("window_id", window.id().value())
                        .put("pane_id", pane.id().value())
                        .put("pane_index", paneIndex));
    }

    private static Session appendTarget(Main.Context context, Server server) {
        authenticate(context, server);
        PaneId current = currentPane(context);
        return server.panes().stream()
                .filter(pane -> pane.id().equals(current))
                .findFirst()
                .orElseThrow(() -> Main.usage("TMUX_PANE does not resolve on the selected server"))
                .window()
                .session();
    }

    /** The pane this process runs in, already checked for its sigil by the inherited context. */
    private static PaneId currentPane(Main.Context context) {
        return inheritedContext(context)
                .pane()
                .orElseThrow(() -> Main.usage("TMUX_PANE does not resolve on the selected server"));
    }

    /**
     * What tmux told this process about where it is running.
     *
     * <p>The two variables are diagnosed apart: a {@code TMUX} that does not parse is neither a
     * different server nor a missing terminal, and a {@code TMUX_PANE} that is not an id is neither
     * of those either.
     */
    private static io.github.libtmux.TmuxEnvironment inheritedContext(Main.Context context) {
        Map<String, String> environment = context.environment();
        String pane = environment.getOrDefault("TMUX_PANE", "");
        if (!pane.isEmpty())
            try {
                PaneId unused = new PaneId(pane);
            } catch (IllegalArgumentException notAnId) {
                throw Main.usage("TMUX_PANE is not a tmux pane id such as %0: " + pane);
            }
        return io.github.libtmux.TmuxEnvironment.of(environment)
                .orElseThrow(() -> Main.usage(
                        "TMUX is not shaped socket,pid,session: " + environment.getOrDefault("TMUX", "(unset)")));
    }

    private static void authenticate(Main.Context context, Server selected) {
        if (!sameDaemon(context, selected))
            throw Main.usage("selected server does not match the inherited tmux daemon");
    }

    private static boolean sameDaemon(Main.Context context, Server selected) {
        var inherited = inheritedContext(context);
        try (Server original = Server.open(selected.config().toBuilder()
                .endpoint(ServerEndpoint.socketPath(inherited.socket()))
                .build())) {
            String identity;
            try {
                identity = original.expand("#{pid}:#{start_time}");
            } catch (io.github.libtmux.LibTmuxException unreachable) {
                throw Main.usage(
                        "the tmux server TMUX names is not running: " + inherited.socket() + "; load detached with -d");
            }
            if (!identity.startsWith(inherited.serverPid() + ":"))
                throw Main.usage(
                        "the tmux server TMUX names has restarted since this shell started; load detached with -d");
            return selected.isAlive() && identity.equals(selected.expand("#{pid}:#{start_time}"));
        }
    }

    /**
     * Where an attached load ends up: outside tmux ({@code insideTmux} false), switched via a known
     * client, or switched blind when the invoking pane cannot be identified.
     */
    private record AttachTarget(boolean insideTmux, Optional<io.github.libtmux.Client> client) {}

    /**
     * Decided before anything is built: every way the invoking context can fail to carry this load
     * is refused here, so a context that cannot be honored never leaves a session behind.
     */
    private static AttachTarget attachTarget(Main.Context context, Server selected) {
        if (!context.environment().containsKey("TMUX")) return new AttachTarget(false, Optional.empty());
        if (!sameDaemon(context, selected))
            throw Main.usage(
                    "the current tmux pane's server is not the server selected to load onto; load detached with -d");
        Optional<PaneId> pane = inheritedContext(context).pane();
        // A run-shell key binding sets TMUX but no TMUX_PANE, and switches whichever client tmux
        // considers current. There is no pane to check in that shape.
        if (pane.isEmpty()) return new AttachTarget(true, Optional.empty());
        PaneId invoking = pane.orElseThrow();
        Pane current = selected.panes().stream()
                .filter(candidate -> candidate.id().equals(invoking))
                .findFirst()
                .orElseThrow(() -> Main.usage("TMUX_PANE names a pane this tmux server does not have: "
                        + invoking.value() + "; load detached with -d"));
        if (current.expand("#{pane_tty}").isBlank())
            throw Main.usage(
                    "the pane TMUX_PANE names has no terminal: " + invoking.value() + "; load detached with -d");
        var host = current.window().session().id();
        var attached = selected.clients().stream()
                .filter(client -> client.attachment().isPresent()
                        && client.attachment().orElseThrow().session().id().equals(host))
                .toList();
        if (attached.isEmpty())
            throw Main.usage("no tmux client is attached to the session the current pane belongs to; "
                    + "load detached with -d");
        var exact = attached.stream()
                .filter(client ->
                        client.attachment().orElseThrow().activePane().id().equals(invoking))
                .toList();
        return new AttachTarget(true, exact.size() == 1 ? Optional.of(exact.getFirst()) : Optional.empty());
    }

    /** A single-character reply, lower-cased, or {@code fallback} for a blank line. */
    private static char promptAnswer(Main.Context context, String prompt, char fallback) throws IOException {
        context.error().write(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        context.error().flush();
        String line = new java.io.BufferedReader(
                        new java.io.InputStreamReader(context.input(), java.nio.charset.StandardCharsets.UTF_8))
                .readLine();
        return line == null || line.strip().isEmpty()
                ? fallback
                : Character.toLowerCase(line.strip().charAt(0));
    }

    private static void attach(Server server, Session session, Main.Context context, AttachTarget target)
            throws IOException, InterruptedException {
        if (target.insideTmux()) {
            authenticate(context, server);
            if (target.client().isPresent()) {
                var client = target.client().orElseThrow();
                PaneId pane = currentPane(context);
                var attachment = client.fetchAttachment();
                if (attachment.isEmpty()
                        || !attachment.orElseThrow().activePane().id().equals(pane)) {
                    throw Main.usage("invoking tmux client changed during load; workspace remains loaded");
                }
                client.switchTo(session);
            } else {
                // The invoking pane could not be identified (a run-shell binding sets TMUX but not
                // TMUX_PANE): switch with no -c and let tmux pick its own most recent client.
                try {
                    server.run(List.of("switch-client", "-t", session.id().value()));
                } catch (io.github.libtmux.LibTmuxException unswitchable) {
                    throw new Main.Failure(
                            "tmux_failed",
                            1,
                            "the workspace loaded, but tmux had no client to move to it; attach with: tmux attach -t "
                                    + session.name());
                }
            }
            return;
        }
        var argv = new ArrayList<>(server.config().endpointCommand());
        argv.add("attach-session");
        argv.add("-t");
        argv.add(session.id().value());
        // This process's own descriptors, not a fresh open of the tty by path: a client whose stdio
        // is reopened attaches and reads input but draws nothing.
        ProcessBuilder builder = new ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT);
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
                            .orElseThrow(() -> new Main.Failure(
                                    "session_not_found",
                                    1,
                                    name.isEmpty() ? "select a live session by name" : "no session named " + name));
            ObjectNode captured = Documents.JSON.createObjectNode().put("session_name", session.name());
            captured.set("options", Documents.JSON.valueToTree(session.options().all()));
            String defaultShell = session.options().get("default-shell").orElse("");
            ArrayNode windows = captured.putArray("windows");
            for (Window window : session.windows()) {
                ObjectNode node = windows.addObject()
                        .put("window_name", window.name())
                        .put("window_index", window.index().value())
                        .put("layout", window.layout().value())
                        .put("focus", window.active());
                node.set(
                        "options_after",
                        Documents.JSON.valueToTree(window.options().all()));
                ArrayNode panes = node.putArray("panes");
                for (Pane pane : window.panes()) {
                    ObjectNode paneNode = panes.addObject()
                            .put("start_directory", pane.currentPath().toString())
                            .put("focus", pane.active());
                    String command = pane.currentCommand();
                    // Round-trips faithfully: reloading this document must not run the session's
                    // own default shell as an explicit pane command, which would put a shell inside
                    // a shell. Anything else the pane runs is emitted so reloading recreates it.
                    if (!sameShell(command, defaultShell))
                        paneNode.putArray("shell_command").add(command);
                }
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
            if (destination.isEmpty()) {
                if (!report.machine())
                    throw Main.usage("freeze writes where --save-to says, or to stdout with --json or --ndjson");
                if (report.streaming()) {
                    result.set("workspace", captured);
                    report.event("completed", result);
                } else report.document(captured);
            } else {
                // An explicit --save-to is consent to that destination; --force alone governs
                // replacing an existing file, with or without a terminal.
                Path path = context.directory().resolve(Catalog.expand(context, destination));
                try {
                    Documents.write(path, captured, format, Main.flag(args, "--force"));
                } catch (java.nio.file.FileAlreadyExistsException exists) {
                    throw new Main.Failure(
                            "destination_exists", 1, "destination already exists: " + Catalog.mask(context, path));
                }
                result.put("destination", Catalog.mask(context, path)).put("format", format);
                if (report.streaming()) report.event("completed", result);
                else if (report.machine()) report.document(result);
                else if (!Main.flag(args, "--quiet")) report.line("success", "Saved", Catalog.mask(context, path));
            }
        }
    }

    /** Whether a pane's reported command and the session's default shell name the same program. */
    private static boolean sameShell(String command, String defaultShell) {
        String left = shellName(command);
        String right = shellName(defaultShell);
        return !left.isEmpty() && left.equals(right);
    }

    /** A shell name stripped of tmux's login-shell {@code -} prefix and any directory. */
    private static String shellName(String value) {
        String stripped = value.startsWith("-") ? value.substring(1) : value;
        int slash = stripped.lastIndexOf('/');
        return slash < 0 ? stripped : stripped.substring(slash + 1);
    }
}
