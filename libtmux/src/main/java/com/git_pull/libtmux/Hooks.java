package com.git_pull.libtmux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tmux hooks at one scope.
 *
 * <p>A hook binds a tmux command to an event, so tmux runs it without a client asking.
 *
 * <p>Every hook is an array: several commands can hang off one event, and tmux runs them in order.
 * That is why a listing answers with a list per event rather than a value — a hook set once is a
 * list of one, not a special case.
 *
 * <p>Each hook lives at a particular scope, and not every hook lives at every one.
 * {@code pane-focus-in} is a window hook, {@code alert-bell} a session hook. Setting one at a scope
 * it does not belong to is accepted and then silently discarded, on every supported release — so a
 * hook that never fires is worth checking against {@link #all()} before it is worth debugging.
 */
public final class Hooks {

    private final Server server;
    private final List<String> scope;

    private Hooks(Server server, List<String> scope) {
        this.server = server;
        this.scope = scope;
    }

    static Hooks global(Server server) {
        return new Hooks(server, List.of("-g"));
    }

    static Hooks session(Server server, SessionId session) {
        return new Hooks(server, List.of("-t", session.value()));
    }

    static Hooks window(Server server, WindowId window) {
        return new Hooks(server, List.of("-w", "-t", window.value()));
    }

    static Hooks pane(Server server, PaneId pane) {
        return new Hooks(server, List.of("-p", "-t", pane.value()));
    }

    /**
     * Binds a tmux command to an event at this scope, replacing anything already bound to it.
     *
     * <p>Replacing, not adding: tmux discards the whole array. {@link #append} is how a second
     * command joins the first.
     */
    public void set(String event, String command) {
        server.run(argv("set-hook", List.of(event, command)));
    }

    /** Binds another command to an event, after whatever is already bound to it. */
    public void append(String event, String command) {
        server.run(argv("set-hook", List.of("-a", event, command)));
    }

    /** Removes everything bound to an event at this scope. */
    public void unset(String event) {
        server.run(argv("set-hook", List.of("-u", event)));
    }

    /**
     * Runs what is bound to an event now, without waiting for the event.
     *
     * <p>tmux spells this {@code set-hook -R}, which reads as setting something. It runs the hook
     * and binds nothing.
     */
    public void run(String event) {
        server.run(argv("set-hook", List.of("-R", event)));
    }

    /**
     * Every hook at this scope, keyed by event, each with its commands in the order tmux runs them.
     *
     * <p>tmux prints one line per entry, subscripted — {@code after-new-window[0]}. The subscript is
     * a position in the list rather than part of the name, so it becomes the ordering here and the
     * key stays the event a caller would look up.
     */
    public Map<String, List<String>> all() {
        Map<String, List<String>> hooks = new LinkedHashMap<>();
        for (String line : server.run(argv("show-hooks", List.of())).stdout()) {
            int split = line.indexOf(' ');
            if (split <= 0) {
                continue;
            }
            String subscripted = line.substring(0, split);
            int open = subscripted.indexOf('[');
            String event = open < 0 ? subscripted : subscripted.substring(0, open);
            hooks.computeIfAbsent(event, key -> new ArrayList<>()).add(line.substring(split + 1));
        }
        hooks.replaceAll((event, commands) -> List.copyOf(commands));
        return Collections.unmodifiableMap(hooks);
    }

    private List<String> argv(String command, List<String> tail) {
        List<String> argv = new ArrayList<>(1 + scope.size() + tail.size());
        argv.add(command);
        argv.addAll(scope);
        argv.addAll(tail);
        return argv;
    }
}
