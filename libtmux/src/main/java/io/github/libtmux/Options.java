package io.github.libtmux;

import io.github.libtmux.batch.Batch;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.batch.OperationResult;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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

    /** Under the ceiling {@link Batch#length()} describes, with room for the guard around it. */
    private static final int GROUP_BUDGET = 15_000;

    private final Server server;
    private final @Nullable ServerSnapshot snapshot;
    private final List<String> scope;

    private Options(Server server, @Nullable ServerSnapshot snapshot, List<String> scope) {
        this.server = server;
        this.snapshot = snapshot;
        this.scope = scope;
    }

    static Options server(Server server) {
        return new Options(server, null, List.of("-s"));
    }

    static Options global(Server server) {
        return new Options(server, null, List.of("-g"));
    }

    static Options session(Server server, ServerSnapshot snapshot, SessionId session) {
        return new Options(server, snapshot, List.of("-t", session.value()));
    }

    static Options window(Server server, ServerSnapshot snapshot, WindowId window) {
        return new Options(server, snapshot, List.of("-w", "-t", window.value()));
    }

    static Options pane(Server server, ServerSnapshot snapshot, PaneId pane) {
        return new Options(server, snapshot, List.of("-p", "-t", pane.value()));
    }

    /**
     * The value in effect at this scope, inherited from a parent scope when this one does not set it.
     *
     * <p>This is what tmux itself will act on. To ask the narrower question — whether this scope
     * sets the option at all — look for the name in {@link #all()}, which lists only what is set
     * here.
     *
     * @return empty only when tmux does not know the option, which it reports as an error; an option
     *     genuinely set to the empty string comes back as an empty value, not as absent. A value
     *     spanning several lines comes back whole
     */
    public Optional<String> get(String name) {
        var result = cmd(argv("show-options", List.of("-A", "-v", name)));
        if (!result.succeeded()) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n", result.stdout()));
    }

    /** Every option set at this scope, in tmux's order. Inherited values are not listed. */
    public Map<String, String> all() {
        return read(List.of());
    }

    /**
     * Names from the listing, values from {@code -v}, in one further invocation.
     *
     * <p>A listed value is escaped with {@code vis(3)} and wrapped in whichever quotes that release
     * chose, and which characters it reaches changed inside the supported range — {@code a$b} prints
     * as {@code "a\$b"} on 3.2a and {@code "a\\$b"} on 3.4. {@code -v} prints the value itself on
     * every release, which is also what {@link #get} reads, so the two agree.
     */
    private Map<String, String> read(List<String> flags) {
        List<String> names = new ArrayList<>();
        for (String line : run(argv("show-options", flags)).stdout()) {
            int split = line.indexOf(' ');
            names.add(inherited(split < 0 ? line : line.substring(0, split)));
        }
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int from = 0; from < names.size(); ) {
            int to = from;
            // -q so an option unset between the two requests reads as empty rather than ending the batch.
            Batch batch = snapshot == null ? server.batch() : server.batch(snapshot);
            do {
                batch.add(argv("show-options", List.of("-q", "-v", names.get(to++))));
            } while (to < names.size() && batch.length() < GROUP_BUDGET);
            record(names.subList(from, to), batch, options);
            from = to;
        }
        return Collections.unmodifiableMap(options);
    }

    private static void record(List<String> names, Batch batch, Map<String, String> into) {
        List<OperationResult> read = batch.run().operations();
        for (int index = 0; index < names.size(); index++) {
            OperationResult value = read.get(index);
            if (value.outcome() != OperationOutcome.COMPLETE) {
                throw new LibTmuxException(
                        "tmux could not read option " + names.get(index) + ": " + String.join("; ", value.stderr()));
            }
            into.put(names.get(index), String.join("\n", value.stdout()));
        }
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
        run(argv("set-option", List.of(name, value)));
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
        return cmd(argv("set-option", List.of("-o", name, value))).succeeded();
    }

    /**
     * Adds to the end of a string option, rather than replacing it.
     *
     * <p>Appending to an option this scope has not set simply sets it, which is what tmux does and
     * what a caller building a value up piece by piece wants.
     */
    public void append(String name, String suffix) {
        run(argv("set-option", List.of("-a", name, suffix)));
    }

    /**
     * Sets one option to what a tmux format comes to, rather than to the format itself.
     *
     * <p>{@code setExpanded("status-left", "in #{session_name}")} stores {@code in base}. The
     * expansion happens once, when this is called; the option does not stay live.
     *
     * <p>The value is expanded by tmux every time the option is read, which is what this is for.
     * That also means {@code #(...)} in it runs a command, so pass any interpolated value through
     * {@link TmuxFormats#literal} unless you mean it to be expanded.
     */
    public void setExpanded(String name, String format) {
        run(argv("set-option", List.of("-F", name, format)));
    }

    /** Removes one option at this scope, so it falls back to whatever it inherits. */
    public void unset(String name) {
        run(argv("set-option", List.of("-u", name)));
    }

    private CommandResult cmd(List<String> argv) {
        return snapshot == null ? server.cmd(argv) : server.cmd(snapshot, argv);
    }

    private CommandResult run(List<String> argv) {
        return snapshot == null ? server.run(argv) : server.run(snapshot, argv);
    }

    private List<String> argv(String command, List<String> tail) {
        List<String> argv = new ArrayList<>(1 + scope.size() + tail.size());
        argv.add(command);
        argv.addAll(scope);
        argv.addAll(tail);
        return argv;
    }
}
