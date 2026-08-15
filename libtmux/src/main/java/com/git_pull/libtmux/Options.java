package com.git_pull.libtmux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tmux options at one scope.
 *
 * <p>tmux keeps options on the server, on a session, on a window and on a pane, and the same option
 * name can exist at more than one of them. A scope is therefore chosen when the view is obtained,
 * not passed to every call, so a caller cannot read one scope and write another.
 *
 * <p>Array options keep the subscript tmux prints — {@code command-alias[0]} — because that is what
 * addresses the individual entry when setting it back.
 */
public final class Options {

    private final Server server;
    private final List<String> scope;

    private Options(Server server, List<String> scope) {
        this.server = server;
        this.scope = scope;
    }

    static Options server(Server server) {
        return new Options(server, List.of("-s"));
    }

    static Options global(Server server) {
        return new Options(server, List.of("-g"));
    }

    static Options session(Server server, SessionId session) {
        return new Options(server, List.of("-t", session.value()));
    }

    static Options window(Server server, WindowId window) {
        return new Options(server, List.of("-w", "-t", window.value()));
    }

    static Options pane(Server server, PaneId pane) {
        return new Options(server, List.of("-p", "-t", pane.value()));
    }

    /**
     * The value in effect at this scope, inherited from a parent scope when this one does not set it.
     *
     * <p>This is what tmux itself will act on. To ask the narrower question — whether this scope
     * sets the option at all — look for the name in {@link #all()}, which lists only what is set
     * here.
     *
     * @return empty only when tmux does not know the option, which it reports as an error; an option
     *     genuinely set to the empty string comes back as an empty value, not as absent
     */
    public Optional<String> get(String name) {
        var result = server.cmd(argv("show-options", List.of("-A", "-v", name)));
        if (!result.succeeded()) {
            return Optional.empty();
        }
        return Optional.of(result.stdout().isEmpty() ? "" : result.stdout().get(0));
    }

    /** Every option set at this scope, in tmux's order. Inherited values are not listed. */
    public Map<String, String> all() {
        return read(List.of());
    }

    private Map<String, String> read(List<String> flags) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String line : server.run(argv("show-options", flags)).stdout()) {
            int split = line.indexOf(' ');
            if (split < 0) {
                // A flag option prints its name alone when set and nothing when unset.
                options.put(inherited(line), "");
            } else {
                options.put(inherited(line.substring(0, split)), unquote(line.substring(split + 1)));
            }
        }
        return Collections.unmodifiableMap(options);
    }

    /**
     * Drops the marker tmux puts on an option a wide listing found on a parent scope.
     *
     * <p>{@code show-options -A} prints {@code status-left*} for a value this scope inherits rather
     * than sets. Keeping the star would mean the name a caller looks up is not the name they get
     * back, and it carries nothing {@link #all()} does not already answer.
     */
    private static String inherited(String name) {
        return name.endsWith("*") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Every option in effect at this scope, including the ones inherited rather than set here.
     *
     * <p>The wide counterpart to {@link #all()}: what tmux will act on, which for a session that
     * sets nothing of its own is everything and not nothing.
     */
    public Map<String, String> effective() {
        return read(List.of("-A"));
    }

    /** Sets one option at this scope. */
    public void set(String name, String value) {
        server.run(argv("set-option", List.of(name, value)));
    }

    /**
     * Sets one option only if this scope does not already set it.
     *
     * <p>tmux reports the already-set case as an error, which it is not: declining to overwrite is
     * the whole point of asking. The answer comes back as a value instead.
     *
     * @return whether the value was taken, false when this scope already set the option
     */
    public boolean setIfAbsent(String name, String value) {
        return server.cmd(argv("set-option", List.of("-o", name, value))).succeeded();
    }

    /**
     * Adds to the end of a string option, rather than replacing it.
     *
     * <p>Appending to an option this scope has not set simply sets it, which is what tmux does and
     * what a caller building a value up piece by piece wants.
     */
    public void append(String name, String suffix) {
        server.run(argv("set-option", List.of("-a", name, suffix)));
    }

    /**
     * Sets one option to what a tmux format comes to, rather than to the format itself.
     *
     * <p>{@code setExpanded("status-left", "in #{session_name}")} stores {@code in base}. The
     * expansion happens once, when this is called; the option does not stay live.
     */
    public void setExpanded(String name, String format) {
        server.run(argv("set-option", List.of("-F", name, format)));
    }

    /** Removes one option at this scope, so it falls back to whatever it inherits. */
    public void unset(String name) {
        server.run(argv("set-option", List.of("-u", name)));
    }

    private List<String> argv(String command, List<String> tail) {
        List<String> argv = new ArrayList<>(1 + scope.size() + tail.size());
        argv.add(command);
        argv.addAll(scope);
        argv.addAll(tail);
        return argv;
    }

    /** tmux quotes a value that contains spaces or specials; a caller wants the value itself. */
    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
