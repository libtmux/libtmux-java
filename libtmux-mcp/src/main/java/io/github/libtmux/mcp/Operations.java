package io.github.libtmux.mcp;

import io.github.libtmux.Dimensions;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.SessionSpec;
import io.github.libtmux.SplitSpec;
import io.github.libtmux.Window;
import io.github.libtmux.WindowSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Small typed adapters for the capability-model inventory. */
final class Operations {

    private Operations() {}

    static Object serverInfo(Call call) {
        Server server = call.server();
        boolean running = server.isAlive();
        return values(
                "running",
                running,
                "identity",
                server.identity().toString(),
                "version",
                running ? server.version().toString() : "unknown",
                "sessions",
                running ? server.sessions().size() : 0);
    }

    static Object sessionInfo(Call call) {
        return session(Targets.sessionById(call.server(), call.string("session_id")));
    }

    static Object windowInfo(Call call) {
        return window(Targets.window(call.server(), call.string("window_id")));
    }

    static Object paneInfo(Call call) {
        return pane(Targets.pane(call.server(), call.string("pane_id")));
    }

    static Object snapshotPane(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        Reading.Captured capture = Reading.capture(call);
        return values(
                "pane",
                pane(pane),
                "content",
                capture.content(),
                "cursor",
                capture.cursor(),
                "truncated",
                capture.truncated(),
                "lines_dropped",
                capture.linesDropped());
    }

    static Object findPaneByPosition(Call call) {
        Window window = Targets.window(call.server(), call.string("window_id"));
        String position = call.string("position").toLowerCase(Locale.ROOT);
        Pane found = window.panes().stream()
                .filter(pane -> switch (position) {
                    case "top-left" -> pane.edges().top() && pane.edges().left();
                    case "top-right" -> pane.edges().top() && pane.edges().right();
                    case "bottom-left" -> pane.edges().bottom() && pane.edges().left();
                    case "bottom-right" -> pane.edges().bottom() && pane.edges().right();
                    default ->
                        throw new IllegalArgumentException("unknown position '" + position
                                + "'; expected top-left, top-right, bottom-left or bottom-right");
                })
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("window " + window.id().value() + " has no pane at " + position));
        return pane(found);
    }

    static Object tmuxVariables(Call call) {
        List<String> names = call.strings("names");
        Map<String, String> variables = call.maybe("pane")
                .map(target -> Targets.pane(call.server(), target).variables(names))
                .orElseGet(() -> call.server().variables(names));
        return values("values", variables);
    }

    static Object showOption(Call call) {
        String name = call.string("name");
        Settings.OptionValues values = Settings.showOptions(call);
        return values(
                "scope",
                values.scope(),
                "target",
                values.target() == null ? "" : values.target(),
                "name",
                name,
                "value",
                values.options().getOrDefault(name, ""));
    }

    static Object showHooks(Call call) {
        Settings.HookValues values = Settings.showHooks(call);
        @Nullable String name = call.maybe("name").orElse(null);
        Map<String, List<String>> hooks = name == null
                ? values.hooks()
                : values.hooks().containsKey(name) ? Map.of(name, values.hooks().get(name)) : Map.of();
        return values(
                "scope",
                values.scope(),
                "target",
                values.target() == null ? "" : values.target(),
                "count",
                hooks.size(),
                "hooks",
                hooks);
    }

    static Object callReadToolsBatch(Call call) {
        List<Map<String, Object>> operations = call.objects("operations");
        if (operations.isEmpty() || operations.size() > 16) {
            throw new IllegalArgumentException("operations must contain between 1 and 16 calls");
        }
        String onError = call.maybe("onError").orElse("stop");
        if (!Set.of("stop", "continue").contains(onError)) {
            throw new IllegalArgumentException("onError must be stop or continue");
        }
        boolean keepGoing = "continue".equals(onError);
        Set<String> allowed = call.surface().require("call_read_tools_batch").nestedAuthority();
        List<ReadOperation> validated = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            Set<String> unknown = new java.util.LinkedHashSet<>(operation.keySet());
            unknown.removeAll(Set.of("tool", "arguments"));
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("read operation has unknown field(s) " + unknown);
            }
            Object requested = operation.get("tool");
            if (!(requested instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("read operation requires a string 'tool'");
            }
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("tool '" + name + "' is not eligible for read batching");
            }
            ToolSpec nested = Catalog.named(name);
            Map<String, Object> arguments = object(operation.get("arguments"), "arguments");
            nested.validateArguments(arguments);
            validated.add(new ReadOperation(name, nested, arguments));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        @Nullable Integer stoppedAt = null;
        for (int index = 0; index < validated.size(); index++) {
            ReadOperation operation = validated.get(index);
            io.modelcontextprotocol.spec.McpSchema.CallToolResult envelope;
            Object error = com.fasterxml.jackson.databind.node.NullNode.getInstance();
            try {
                Object answer =
                        operation.tool().answer().apply(call.connection().call(operation.arguments(), call.progress()));
                operation.tool().validateOutput(answer);
                envelope = Answers.ok(answer);
            } catch (RuntimeException failure) {
                String message = String.valueOf(failure.getMessage());
                error = message;
                envelope = Answers.failure(message);
            }
            boolean success = !Boolean.TRUE.equals(envelope.isError());
            results.add(values(
                    "index",
                    index,
                    "tool",
                    operation.name(),
                    "success",
                    success,
                    "error",
                    error,
                    "result",
                    Answers.envelope(envelope),
                    "resultTruncated",
                    false));
            if (!success && !keepGoing) {
                stoppedAt = index;
            }
            if (stoppedAt != null) {
                break;
            }
        }
        return batchResult(results, stoppedAt, onError);
    }

    private static Map<String, Object> batchResult(
            List<Map<String, Object>> results, @Nullable Integer stoppedAt, String onError) {
        long succeeded = results.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("success")))
                .count();
        Map<String, Object> result = new LinkedHashMap<>(values(
                "results",
                List.copyOf(results),
                "succeeded",
                Math.toIntExact(succeeded),
                "failed",
                Math.toIntExact(results.size() - succeeded),
                "stoppedAt",
                stoppedAt == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : stoppedAt,
                "truncated",
                false,
                "truncatedBytes",
                0,
                "onError",
                onError));
        return Collections.unmodifiableMap(result);
    }

    private record ReadOperation(String name, ToolSpec tool, Map<String, Object> arguments) {}

    static Object renameSession(Call call) {
        return session(
                Targets.sessionById(call.server(), call.string("session_id")).rename(call.string("new_name")));
    }

    static Object renameWindow(Call call) {
        return window(Targets.window(call.server(), call.string("window_id")).rename(call.string("new_name")));
    }

    static Object selectWindow(Call call) {
        Window window = Targets.window(call.server(), call.string("window_id"));
        window.select();
        return window(window.refresh());
    }

    static Object selectPane(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        pane.select();
        return pane(pane.refresh());
    }

    static Object resizeWindow(Call call) {
        Window window = Targets.window(call.server(), call.string("window_id"));
        window.resizeTo(dimensions(call, window.size()));
        return window(window.refresh());
    }

    static Object moveWindow(Call call) {
        Window window = Targets.window(call.server(), call.string("window_id"));
        Session session = Targets.sessionById(call.server(), call.string("session_id"));
        int index = call.integer("index", -1);
        if (index < 0) {
            window.moveTo(session);
        } else {
            window.moveTo(session, index);
        }
        return values(
                "window_id", window.id().value(), "session_id", session.id().value(), "index", index);
    }

    static Object swapPane(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        Pane other = Targets.pane(call.server(), call.string("other_pane_id"));
        pane.swapWith(other);
        return values("pane_id", pane.id().value(), "other_pane_id", other.id().value());
    }

    static Object setPaneTitle(Call call) {
        return pane(Targets.pane(call.server(), call.string("pane_id")).retitle(call.string("title")));
    }

    static Object setMouseEnabled(Call call) {
        boolean enabled = call.flag("enabled", false);
        call.server().setMouseEnabled(enabled);
        return values("enabled", enabled);
    }

    static Object setHistoryLimit(Call call) {
        int lines = requiredInteger(call, "lines");
        Session session = Targets.sessionById(call.server(), call.string("session_id"));
        session.setHistoryLimit(lines);
        return values("session_id", session.id().value(), "lines", lines);
    }

    static Object createSession(Call call) {
        SessionSpec.Builder spec = SessionSpec.builder();
        call.maybe("session_name").ifPresent(spec::named);
        call.maybe("window_name").ifPresent(spec::firstWindowNamed);
        call.maybe("start_directory").map(Operations::directory).ifPresent(spec::in);
        int width = call.integer("width", -1);
        int height = call.integer("height", -1);
        if ((width < 0) != (height < 0)) {
            throw new IllegalArgumentException("width and height must be supplied together");
        }
        if (width >= 0) {
            spec.sized(new Dimensions(width, height));
        }
        Session made = call.server().newSession(spec.build());
        return session(made);
    }

    static Object createWindow(Call call) {
        Session session = Targets.sessionById(call.server(), call.string("session_id"));
        WindowSpec.Builder spec = WindowSpec.builder();
        call.maybe("window_name").ifPresent(spec::named);
        call.maybe("start_directory").map(Operations::directory).ifPresent(spec::in);
        if (!call.flag("attach", false)) {
            spec.detached();
        }
        call.maybe("direction").ifPresent(direction -> {
            switch (direction.toLowerCase(Locale.ROOT)) {
                case "before" -> spec.before();
                case "after" -> spec.after();
                default -> throw new IllegalArgumentException("direction must be before or after");
            }
        });
        return window(session.newWindow(spec.build()));
    }

    static Object splitWindow(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        SplitSpec.Builder spec = SplitSpec.builder();
        switch (call.maybe("direction").orElse("below").toLowerCase(Locale.ROOT)) {
            case "below", "down" -> spec.below();
            case "above", "up" -> spec.above();
            case "left" -> spec.toLeft();
            case "right" -> spec.toRight();
            default -> throw new IllegalArgumentException("direction must be below, above, left or right");
        }
        int percent = call.integer("percent", 50);
        spec.percent(Math.clamp(percent, 1, 99));
        call.maybe("start_directory").map(Operations::directory).ifPresent(spec::in);
        return pane(pane.split(spec.build()));
    }

    static Object respawnPane(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        call.maybe("start_directory").ifPresentOrElse(path -> pane.respawnIn(directory(path)), pane::respawn);
        return values("pane_id", pane.id().value(), "restarted", true);
    }

    static Object sendKeysBatch(Call call) {
        List<Map<String, Object>> operations = call.objects("operations");
        if (operations.isEmpty() || operations.size() > 64) {
            throw new IllegalArgumentException("operations must contain between 1 and 64 sends");
        }
        String onError = call.maybe("onError").orElse("stop");
        if (!Set.of("stop", "continue").contains(onError)) {
            throw new IllegalArgumentException("onError must be stop or continue");
        }
        boolean keepGoing = "continue".equals(onError);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            Map<String, Object> operation = operations.get(index);
            String paneId = requiredText(operation, "pane_id");
            List<String> resolvedPaneIds = List.of();
            try {
                Pane pane = Targets.pane(call.server(), paneId);
                PaneInputCohort.Resolution cohort = PaneInputCohort.resolve(pane, call.caller());
                resolvedPaneIds = cohort.configuredKeyRecipientIds();
                List<String> keys = strings(operation.get("keys"), "keys");
                boolean literal = booleanValue(operation.get("literal"), false, "literal");
                Typing.sendKeys(pane, keys, literal, cohort);
                results.add(values(
                        "index", index, "pane_id", paneId, "resolved_pane_ids", resolvedPaneIds, "success", true));
            } catch (RuntimeException failure) {
                results.add(values(
                        "index",
                        index,
                        "pane_id",
                        paneId,
                        "resolved_pane_ids",
                        resolvedPaneIds,
                        "success",
                        false,
                        "error",
                        String.valueOf(failure.getMessage())));
                if (!keepGoing) {
                    break;
                }
            }
        }
        return values("results", List.copyOf(results), "completed", results.size());
    }

    static Object setSynchronizePanes(Call call) {
        Window window = Targets.window(call.server(), call.string("window_id"));
        boolean enabled = call.flag("enabled", false);
        if (enabled) {
            window.synchronizePanes();
        } else {
            window.stopSynchronizingPanes();
        }
        return values("window_id", window.id().value(), "enabled", enabled);
    }

    static Object clearPaneScrollback(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        pane.clearHistory();
        return values("pane_id", pane.id().value(), "cleared", true);
    }

    static Object killPane(Call call) {
        return kill(call, call.string("pane_id"));
    }

    static Object killWindow(Call call) {
        return kill(call, call.string("window_id"));
    }

    static Object killSession(Call call) {
        return kill(call, call.string("session_id"));
    }

    private static Object kill(Call call, String target) {
        return Shaping.kill(call.connection()
                .call(Map.of("target", target, "confirm_self", call.flag("confirm_self", false)), call.progress()));
    }

    private static Dimensions dimensions(Call call, Dimensions current) {
        int width = call.integer("width", current.width());
        int height = call.integer("height", current.height());
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        return new Dimensions(width, height);
    }

    private static int requiredInteger(Call call, String name) {
        if (!call.arguments().containsKey(name)) {
            throw new IllegalArgumentException("missing required argument '" + name + "'");
        }
        return call.integer(name, 0);
    }

    private static Path directory(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("start_directory must be an absolute path");
        }
        return path.normalize();
    }

    private static Map<String, Object> session(Session session) {
        return values(
                "id",
                session.id().value(),
                "name",
                session.name(),
                "attached",
                session.attached(),
                "windows",
                session.windows().size());
    }

    private static Map<String, Object> window(Window window) {
        return values(
                "id",
                window.id().value(),
                "index",
                window.index().value(),
                "name",
                window.name(),
                "session_id",
                window.session().id().value(),
                "active",
                window.active(),
                "panes",
                window.panes().size(),
                "size",
                window.size().toString());
    }

    private static Map<String, Object> pane(Pane pane) {
        return values(
                "id",
                pane.id().value(),
                "index",
                pane.index(),
                "window_id",
                pane.window().id().value(),
                "session_id",
                pane.window().session().id().value(),
                "active",
                pane.active(),
                "command",
                pane.currentCommand(),
                "path",
                pane.currentPath().toString(),
                "title",
                pane.title(),
                "size",
                pane.size().toString());
    }

    private static String requiredText(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("missing required field '" + name + "'");
        }
        return value.toString();
    }

    private static Map<String, Object> object(@Nullable Object value, String name) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("'" + name + "' must be an object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, nested) -> copy.put(String.valueOf(key), nested));
        return Collections.unmodifiableMap(copy);
    }

    private static List<String> strings(@Nullable Object value, String name) {
        if (value instanceof List<?> many) {
            List<String> strings = many.stream().map(String::valueOf).toList();
            if (!strings.isEmpty()) {
                return strings;
            }
        } else if (value != null && !value.toString().isEmpty()) {
            return List.of(value.toString());
        }
        throw new IllegalArgumentException("'" + name + "' must contain at least one string");
    }

    private static boolean booleanValue(@Nullable Object value, boolean fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if ("true".equalsIgnoreCase(value.toString()) || "false".equalsIgnoreCase(value.toString())) {
            return Boolean.parseBoolean(value.toString());
        }
        throw new IllegalArgumentException("'" + name + "' must be true or false");
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return Collections.unmodifiableMap(values);
    }
}
