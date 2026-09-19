package io.github.libtmux;

import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.transport.CommandResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The environment tmux gives to processes it starts, at one scope.
 *
 * <p>tmux keeps one environment per server and one per session, and hands the result to every
 * process it spawns afterwards. That is what this is for: a pane opened later sees what is set here,
 * so it is how a program refreshes a value that goes stale in a long-lived session — {@code
 * SSH_AUTH_SOCK} after reconnecting, a token, a display.
 *
 * <p>A scope is chosen when the view is obtained rather than passed to every call, as {@link
 * Options} does, so a caller cannot read one scope and write another. There are only the two scopes,
 * because tmux has only those two.
 *
 * <p>A name is in one of three states, and they are not the same question. It can be <em>set</em> to
 * a value, which {@link #get} answers. It can be <em>removed</em>, which is tmux's way of saying a
 * new process should not inherit it even though the server's own environment has it — {@link
 * #isRemoved} answers that, and {@link #get} is empty for it. Or it can simply be absent, which is
 * both of those empty.
 */
public final class Environment {

    /** tmux marks a removed name by printing it with a leading dash instead of a value. */
    private static final char REMOVED = '-';

    private final Server server;
    private final @Nullable ServerSnapshot snapshot;
    private final List<String> scope;

    private Environment(Server server, @Nullable ServerSnapshot snapshot, List<String> scope) {
        this.server = server;
        this.snapshot = snapshot;
        this.scope = scope;
    }

    static Environment global(Server server) {
        return new Environment(server, null, List.of("-g"));
    }

    static Environment session(Server server, ServerSnapshot snapshot, SessionId session) {
        return new Environment(server, snapshot, List.of("-t", session.value()));
    }

    /**
     * The value set for this name, or empty when it is removed or absent.
     *
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the read otherwise fails
     */
    public Optional<String> get(String name) {
        return entry(name).filter(line -> line.indexOf('=') >= 0).map(Environment::value);
    }

    /**
     * Whether new processes are told not to inherit this name.
     *
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the read otherwise fails
     */
    public boolean isRemoved(String name) {
        return entry(name).filter(line -> line.charAt(0) == REMOVED).isPresent();
    }

    /** Every name set at this scope, in tmux's order. Removed names are not values, so not here. */
    public Map<String, String> all() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines()) {
            if (!line.isEmpty() && line.charAt(0) != REMOVED && line.indexOf('=') >= 0) {
                values.put(name(line), value(line));
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /** Every name this scope tells a new process not to inherit. */
    public Set<String> removed() {
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines()) {
            if (!line.isEmpty() && line.charAt(0) == REMOVED) {
                names.add(line.substring(1));
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * Sets a name to a literal value.
     *
     * <p>The value is not expanded: a {@code #{...}} in it arrives as those characters. {@link
     * #setExpanded} is how to ask for the other thing.
     */
    public void set(String name, String value) {
        run(argv(List.of("--", required(name), value)));
    }

    /** Sets a name to what a tmux format expands to now. */
    public void setExpanded(String name, String format) {
        run(argv(List.of("-F", "--", required(name), format)));
    }

    /** Removes the name from this scope, so it is neither set nor marked. */
    public void unset(String name) {
        run(argv(List.of("-u", "--", required(name))));
    }

    /**
     * Tells processes started after this not to inherit the name.
     *
     * <p>Different from {@link #unset}: the name is remembered, as removed, and tmux subtracts it
     * from what a new process is given rather than simply not adding it.
     */
    public void remove(String name) {
        run(argv(List.of("-r", "--", required(name))));
    }

    /**
     * One name's line as tmux prints it, or empty when tmux does not have the name at all.
     *
     * <p>tmux reports an absent name as a failure, which is the one failure here that is an answer;
     * anything else it says is a read that did not happen and is raised.
     */
    private Optional<String> entry(String name) {
        CommandResult result = cmd(showArgv(List.of("--", required(name))));
        if (result.succeeded()) {
            return result.stdout().stream().filter(line -> !line.isEmpty()).findFirst();
        }
        if (result.stderr().stream().anyMatch(Server::serverAbsent)) {
            throw new ServerNotRunningException("no tmux server is answering on this endpoint");
        }
        if (result.stderr().stream().anyMatch(line -> line.contains("unknown variable"))) {
            return Optional.empty();
        }
        throw server.failed("show-environment", result);
    }

    private List<String> lines() {
        return run(showArgv(List.of())).stdout();
    }

    private static String name(String line) {
        return line.substring(0, line.indexOf('='));
    }

    private static String value(String line) {
        return line.substring(line.indexOf('=') + 1);
    }

    /** tmux splits a name at the first {@code =}, so one inside it would set a different name. */
    private static String required(String name) {
        if (name.isEmpty() || name.indexOf('=') >= 0) {
            throw new IllegalArgumentException("not a variable name: '" + name + "'");
        }
        return name;
    }

    private List<String> argv(List<String> tail) {
        return command("set-environment", tail);
    }

    private List<String> showArgv(List<String> tail) {
        return command("show-environment", tail);
    }

    private List<String> command(String command, List<String> tail) {
        List<String> argv = new java.util.ArrayList<>(1 + scope.size() + tail.size());
        argv.add(command);
        argv.addAll(scope);
        argv.addAll(tail);
        return argv;
    }

    private CommandResult cmd(List<String> argv) {
        return snapshot == null ? server.cmd(argv) : server.cmd(snapshot, argv);
    }

    private CommandResult run(List<String> argv) {
        return snapshot == null ? server.run(argv) : server.run(snapshot, argv);
    }
}
