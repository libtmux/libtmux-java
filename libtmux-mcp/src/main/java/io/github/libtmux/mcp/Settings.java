package io.github.libtmux.mcp;

import io.github.libtmux.Hooks;
import io.github.libtmux.Options;
import io.github.libtmux.Server;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Options, hooks and the environment tmux passes on.
 *
 * <p>tmux keeps four sets of options and lets a lower one override the one above it, so "what is
 * this option set to" has four different answers and asking the wrong one explains nothing. Every
 * tool here says which scope it read.
 *
 * <p>Hooks are readable and not writable. A hook set through this server would live only as long as
 * the server does, which is until the client restarts — long enough to be surprising and not long
 * enough to be useful. A hook that should survive belongs in a tmux config file.
 */
final class Settings {

    private Settings() {}

    record OptionValues(String scope, @Nullable String target, int count, Map<String, String> options) {}

    record OptionSet(String scope, @Nullable String target, String name, String value) {}

    record HookValues(String scope, @Nullable String target, int count, Map<String, List<String>> hooks, String note) {}

    record Environment(String session, int count, Map<String, String> variables) {}

    static OptionValues showOptions(Call call) {
        String scope = call.maybe("scope").orElse("global").toLowerCase(Locale.ROOT);
        String target = call.maybe("target").orElse(null);
        Options options = optionsFor(call.server(), scope, target);
        Map<String, String> values = call.flag("effective", false) ? options.effective() : options.all();
        return new OptionValues(scope, target, values.size(), values);
    }

    static OptionSet setOption(Call call) {
        String scope = call.maybe("scope").orElse("global").toLowerCase(Locale.ROOT);
        String target = call.maybe("target").orElse(null);
        String name = call.string("name");
        String value = call.string("value");
        optionsFor(call.server(), scope, target).set(name, value);
        return new OptionSet(scope, target, name, value);
    }

    static HookValues showHooks(Call call) {
        String scope = call.maybe("scope").orElse("global").toLowerCase(Locale.ROOT);
        String target = call.maybe("target").orElse(null);
        Map<String, List<String>> hooks = hooksFor(call.server(), scope, target).all();
        return new HookValues(
                scope,
                target,
                hooks.size(),
                hooks,
                "Read-only here. A hook set over MCP would be gone when this server restarts; put one that "
                        + "should last in a tmux config file.");
    }

    static Environment environment(Call call) {
        String name = call.maybe("session").orElse(null);
        if (name == null) {
            Map<String, String> global = readEnvironment(call.server(), null);
            return new Environment("(global)", global.size(), global);
        }
        var session = Targets.session(call.server(), name);
        Map<String, String> variables = readEnvironment(call.server(), session.name());
        return new Environment(session.name(), variables.size(), variables);
    }

    private static Map<String, String> readEnvironment(Server server, @Nullable String session) {
        List<String> argv =
                session == null ? List.of("show-environment", "-g") : List.of("show-environment", "-t", session);
        return server.cmd(argv).stdout().stream()
                .filter(line -> line.indexOf('=') > 0)
                .collect(java.util.stream.Collectors.toMap(
                        line -> line.substring(0, line.indexOf('=')),
                        line -> line.substring(line.indexOf('=') + 1),
                        (first, second) -> second,
                        java.util.LinkedHashMap::new));
    }

    private static Options optionsFor(Server server, String scope, @Nullable String target) {
        return switch (scope) {
            case "global" -> server.globalOptions();
            case "server" -> server.options();
            case "session" -> Targets.session(server, required(target, scope)).options();
            case "window" -> Targets.window(server, required(target, scope)).options();
            case "pane" -> Targets.pane(server, required(target, scope)).options();
            default ->
                throw new IllegalArgumentException(
                        "'" + scope + "' is not a scope; use global, server, session, window or pane");
        };
    }

    private static Hooks hooksFor(Server server, String scope, @Nullable String target) {
        return switch (scope) {
            case "global", "server" -> server.hooks();
            case "session" -> Targets.session(server, required(target, scope)).hooks();
            case "window" -> Targets.window(server, required(target, scope)).hooks();
            case "pane" -> Targets.pane(server, required(target, scope)).hooks();
            default ->
                throw new IllegalArgumentException(
                        "'" + scope + "' is not a scope; use global, server, session, window or pane");
        };
    }

    private static String required(@Nullable String target, String scope) {
        if (target == null) {
            throw new IllegalArgumentException(
                    "scope '" + scope + "' needs a 'target': the " + scope + " to read it from");
        }
        return target;
    }
}
