package io.github.libtmux;

import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.transport.CommandResult;
import java.util.ArrayList;
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

    /**
     * tmux prints the environment as shell, and that is the only form of it that can be read back.
     *
     * <p>Its plain form puts one name per line, which is not a fact about the value: a value holding
     * a newline occupies several lines and nothing marks where it ends. A value of {@code
     * "first\nSECOND=injected\n-PATH"} read back as {@code first}, invented a variable called
     * {@code SECOND}, and reported {@code PATH} as one this session refuses to pass on. All three
     * from one read of a value tmux had stored perfectly.
     *
     * <p>{@code -s} is unambiguous instead: a value is quoted and its {@code $}, backtick, {@code "}
     * and {@code \} are escaped, so the only unescaped quote is the one that ends it. Present since
     * 3.2a, where the flag and the two format strings are already what they are now. One release
     * writes it differently — see {@link #repaired}.
     */
    private static final List<String> SHELL_FORM = List.of("-s");

    /** How tmux ends a value: the closing quote, then the export that makes it shell. */
    private static final String EXPORTED = "\"; export ";

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
        return entry(name).flatMap(Entry::value);
    }

    /**
     * Whether new processes are told not to inherit this name.
     *
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if the read otherwise fails
     */
    public boolean isRemoved(String name) {
        return entry(name).filter(Entry::removed).isPresent();
    }

    /** Every name set at this scope, in tmux's order. Removed names are not values, so not here. */
    public Map<String, String> all() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> entry : listing().entrySet()) {
            entry.getValue().value().ifPresent(value -> values.put(entry.getKey(), value));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * What a process started from this scope is actually handed, both scopes taken together.
     *
     * <p>{@link #all} answers for one scope, which is what a caller writing to it needs and not what
     * a pane opened later will see: tmux gives a new process the server's environment with the
     * session's laid over it, minus whatever the session marked as not to be inherited. At the
     * server scope the two are the same thing.
     *
     * <p>The counterpart to {@link Options#effective()}, and named after it.
     *
     * @throws ServerNotRunningException if no daemon is running
     * @throws LibTmuxException if a read otherwise fails
     */
    public Map<String, String> effective() {
        if (snapshot == null) {
            return all();
        }
        Map<String, String> merged = new LinkedHashMap<>(server.environment().all());
        merged.keySet().removeAll(removed());
        merged.putAll(all());
        return Collections.unmodifiableMap(merged);
    }

    /** Every name this scope tells a new process not to inherit. */
    public Set<String> removed() {
        Set<String> names = new LinkedHashSet<>();
        listing().forEach((name, entry) -> {
            if (entry.removed()) {
                names.add(name);
            }
        });
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

    /** What tmux holds for one name: a value, or the mark that says not to pass it on. */
    private record Entry(Optional<String> value, boolean removed) {}

    /**
     * What tmux holds for this name, or empty when it does not have the name at all.
     *
     * <p>tmux reports an absent name as a failure, which is the one failure here that is an answer;
     * anything else it says is a read that did not happen and is raised.
     */
    private Optional<Entry> entry(String name) {
        List<String> argv = new ArrayList<>(showArgv(SHELL_FORM));
        argv.addAll(List.of("--", required(name)));
        CommandResult result = cmd(argv);
        if (result.succeeded()) {
            return Optional.ofNullable(repaired(parse(result.stdout())).get(name));
        }
        if (result.stderr().stream().anyMatch(Server::serverAbsent)) {
            throw new ServerNotRunningException("no tmux server is answering on this endpoint");
        }
        if (result.stderr().stream().anyMatch(line -> line.contains("unknown variable"))) {
            return Optional.empty();
        }
        throw server.failed("show-environment", result);
    }

    private Map<String, Entry> listing() {
        return repaired(parse(run(showArgv(SHELL_FORM)).stdout()));
    }

    /**
     * Undoes the one release that writes a value differently.
     *
     * <p>tmux 3.4 puts a second backslash before a {@code $} that a name begins after, so unescaping
     * leaves one in the value and {@code $HOME} reads back as {@code \$HOME}. Measured: 3.3a, 3.5
     * and 3.7c write one backslash where 3.4 writes two, and all of them write one before a bare
     * {@code $}, a space or a digit. The encoding stays reversible — a value that really holds
     * {@code \$HOME} arrives with one backslash more again — so the extra one can be taken back off.
     *
     * <p>Nothing in the text says which release wrote it, so the version has to. It is only asked
     * when a value carries the shape at all, which is rare enough that an ordinary read never pays
     * for it.
     */
    private Map<String, Entry> repaired(Map<String, Entry> parsed) {
        boolean affected = parsed.values().stream()
                .anyMatch(entry -> entry.value()
                        .filter(value -> extraBackslash(value, 0) >= 0)
                        .isPresent());
        if (!affected) {
            return parsed;
        }
        TmuxVersion running = snapshot == null ? server.version() : server.version(snapshot);
        if (running.major() != 3 || running.minor() != 4) {
            return parsed;
        }
        Map<String, Entry> repaired = new LinkedHashMap<>(parsed.size());
        parsed.forEach((name, entry) -> repaired.put(
                name, new Entry(entry.value().map(Environment::withoutTheExtraBackslash), entry.removed())));
        return repaired;
    }

    /** Where a backslash stands before a {@code $} that a name begins after, or {@code -1}. */
    private static int extraBackslash(String value, int from) {
        for (int index = value.indexOf("\\$", from); index >= 0; index = value.indexOf("\\$", index + 1)) {
            int after = index + 2;
            if (after < value.length() && beginsAName(value.charAt(after))) {
                return index;
            }
        }
        return -1;
    }

    /** What tmux 3.4 puts the extra backslash in front of, and the other releases do not. */
    private static boolean beginsAName(char character) {
        return character == '_' || character == '{' || Character.isLetter(character);
    }

    private static String withoutTheExtraBackslash(String value) {
        StringBuilder plain = new StringBuilder(value.length());
        int at = 0;
        for (int found = extraBackslash(value, at); found >= 0; found = extraBackslash(value, at)) {
            plain.append(value, at, found);
            at = found + 1;
        }
        return plain.append(value, at, value.length()).toString();
    }

    /**
     * Reads tmux's shell form back into names and values.
     *
     * <p>A value runs from the quote after its name to the next unescaped quote, however many lines
     * that crosses. Nothing else can end it: tmux escapes every quote inside a value, so a value
     * carrying {@code "; export EVIL;} is quoted rather than obeyed.
     */
    private static Map<String, Entry> parse(List<String> lines) {
        Map<String, Entry> held = new LinkedHashMap<>();
        String open = null;
        StringBuilder value = new StringBuilder();
        for (String line : lines) {
            if (open == null) {
                if (line.startsWith("unset ") && line.endsWith(";")) {
                    held.put(line.substring("unset ".length(), line.length() - 1), new Entry(Optional.empty(), true));
                    continue;
                }
                int quoted = line.indexOf("=\"");
                if (quoted < 0) {
                    continue;
                }
                open = line.substring(0, quoted);
                value.setLength(0);
                value.append(line, quoted + 2, line.length());
            } else {
                value.append('\n').append(line);
            }
            String ended = ends(value.toString(), open);
            if (ended != null) {
                held.put(open, new Entry(Optional.of(unescape(ended)), false));
                open = null;
            }
        }
        return held;
    }

    /** The value without its terminator, or null while the value is still open. */
    private static @Nullable String ends(String sofar, String name) {
        String terminator = EXPORTED + name + ";";
        if (!sofar.endsWith(terminator)) {
            return null;
        }
        String body = sofar.substring(0, sofar.length() - terminator.length());
        // A quote tmux escaped is part of the value, not the end of it, and a backslash can itself be
        // escaped — so what decides is whether the run of backslashes before the quote is even.
        int backslashes = 0;
        for (int index = body.length() - 1; index >= 0 && body.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return backslashes % 2 == 0 ? body : null;
    }

    /** tmux escapes exactly {@code $}, a backtick, {@code "} and {@code \}, each with one backslash. */
    private static String unescape(String value) {
        StringBuilder plain = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < value.length()) {
                plain.append(value.charAt(++index));
            } else {
                plain.append(character);
            }
        }
        return plain.toString();
    }

    /**
     * tmux splits a name at the first {@code =}, so one inside it would set a different name; and a
     * newline inside one would put the rest of it on a line of its own, where nothing says it is a
     * name rather than a value.
     */
    private static String required(String name) {
        if (name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf('\n') >= 0) {
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
