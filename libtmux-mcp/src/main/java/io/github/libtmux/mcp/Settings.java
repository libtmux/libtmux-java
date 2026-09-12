package io.github.libtmux.mcp;

import io.github.libtmux.Hooks;
import io.github.libtmux.Options;
import io.github.libtmux.Server;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * @param unset names removed with {@code set-environment -r}, which is not the same as never set:
     *     a removed name is withheld from processes that would otherwise inherit it
     */
    record Environment(String session, int count, Map<String, String> variables, List<String> unset) {}

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
        String scope = name == null
                ? "(global)"
                : Targets.sessionNamed(call.server(), name).name();
        List<String> argv = name == null ? List.of("show-environment", "-g") : List.of("show-environment", "-t", scope);
        return parseEnvironment(scope, call.server().cmd(argv).stdout());
    }

    /**
     * Reads {@code show-environment}, keeping a removed variable rather than dropping it.
     *
     * <p>tmux prints a set variable as {@code NAME=value} and one removed with {@code set-environment
     * -r} as {@code -NAME}, with no {@code =} at all. Keeping only lines with an {@code =} discarded
     * the second form silently, so a removed variable could not be told from one never set. Only the
     * first {@code =} separates, because a value may contain more.
     */
    static Environment parseEnvironment(String scope, List<String> lines) {
        Map<String, String> variables = new LinkedHashMap<>();
        List<String> unset = new ArrayList<>();
        for (String line : lines) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                variables.put(line.substring(0, equals), line.substring(equals + 1));
            } else if (line.length() > 1 && line.charAt(0) == '-') {
                unset.add(line.substring(1));
            }
        }
        return new Environment(scope, variables.size(), variables, List.copyOf(unset));
    }

    private static Options optionsFor(Server server, String scope, @Nullable String target) {
        return switch (scope) {
            case "global" -> server.globalOptions();
            case "server" -> server.options();
            case "session" ->
                Targets.sessionNamed(server, required(target, scope)).options();
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
            case "session" ->
                Targets.sessionNamed(server, required(target, scope)).hooks();
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
