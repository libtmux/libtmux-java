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
 *
 * <p>Reads use the captured daemon version when available; otherwise they query the selected
 * daemon before reading values. tmux 3.4 and 3.5 require decoding their escaped listing because
 * their value-only output loses the distinction between control characters and literal escapes.
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
     * @throws LibTmuxException if the selected daemon's encoding cannot be established or its
     *     escaped listing cannot be decoded
     */
    public Optional<String> get(String name) {
        TmuxVersion version = listingVersion();
        if (version != null) {
            var result = cmd(argv("show-options", List.of("-A", name)));
            if (!result.succeeded()) return Optional.empty();
            List<String> values = new ArrayList<>();
            for (String line : result.stdout()) {
                int split = line.indexOf(' ');
                values.add(split < 0 ? "" : listedValue(line.substring(split + 1), version));
            }
            return Optional.of(String.join("\n", values));
        }
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
     * as {@code "a\$b"} on 3.2a and {@code "a\\$b"} on 3.4. Releases 3.4 and 3.5 also escape
     * {@code -v} output ambiguously, so those daemons require the normal listing's quoted spelling.
     */
    private Map<String, String> read(List<String> flags) {
        TmuxVersion version = listingVersion();
        if (version != null) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : run(argv("show-options", flags)).stdout()) {
                int split = line.indexOf(' ');
                String name = inherited(split < 0 ? line : line.substring(0, split));
                values.put(name, split < 0 ? "" : listedValue(line.substring(split + 1), version));
            }
            return Collections.unmodifiableMap(values);
        }
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
            // -q keeps an option unset between the two requests from ending the batch.
            Batch batch = snapshot == null ? server.batch() : server.batch(snapshot);
            do {
                var valueFlags = new ArrayList<>(flags);
                valueFlags.addAll(List.of("-q", "-v", names.get(to++)));
                batch.add(argv("show-options", valueFlags));
            } while (to < names.size() && batch.length() < GROUP_BUDGET);
            record(names.subList(from, to), batch, options);
            from = to;
        }
        return Collections.unmodifiableMap(options);
    }

    /** The selected daemon decides output encoding; a client binary may be a different release. */
    private @Nullable TmuxVersion listingVersion() {
        TmuxVersion version = snapshot == null ? null : snapshot.serverVersion().orElse(null);
        if (version == null) {
            var result = cmd(List.of("display-message", "-p", "#{version}"));
            // A daemon that cannot answer cannot serve the read either, and that read reports the
            // failure the way the scope always has.
            if (!result.succeeded()) return null;
            try {
                version = TmuxVersion.parse(String.join("\n", result.stdout()));
            } catch (IllegalArgumentException invalid) {
                throw new LibTmuxException("could not establish tmux option output encoding", invalid);
            }
        }
        return version.major() == 3 && (version.minor() == 4 || version.minor() == 5) ? version : null;
    }

    /** Inverts args_escape for the releases whose outer print pass loses raw-value boundaries. */
    private static String listedValue(String text, TmuxVersion version) {
        if (!text.isEmpty() && text.charAt(0) != '\'' && text.charAt(0) != '"' && text.indexOf(' ') >= 0) {
            // String options with spaces are quoted. Hook command lists retain their tmux syntax.
            return text;
        }
        if (version.minor() == 4) {
            text = text.replaceAll("\\\\(?=\\$[A-Za-z_{])", "");
        }
        int start = 0;
        int end = text.length();
        if (end > 0 && (text.charAt(0) == '\'' || text.charAt(0) == '"')) {
            if (end < 2 || text.charAt(end - 1) != text.charAt(0)) {
                throw new LibTmuxException("tmux returned an unterminated quoted option value");
            }
            start++;
            end--;
        }
        StringBuilder value = new StringBuilder();
        for (int index = start; index < end; index++) {
            char ch = text.charAt(index);
            if (ch != '\\') {
                value.append(ch);
                continue;
            }
            if (++index == end) throw new LibTmuxException("tmux returned an incomplete option escape");
            char escaped = text.charAt(index);
            if (escaped >= '0' && escaped <= '7') {
                int octal = escaped - '0';
                for (int digits = 1; digits < 3 && index + 1 < end; digits++) {
                    char next = text.charAt(index + 1);
                    if (next < '0' || next > '7') break;
                    octal = octal * 8 + next - '0';
                    index++;
                }
                if (octal > 255) throw new LibTmuxException("tmux returned an invalid octal option escape");
                if (octal >= 128)
                    value.append("\\x").append(java.util.HexFormat.of().toHexDigits((byte) octal));
                else value.append((char) octal);
            } else {
                value.append(
                        switch (escaped) {
                            case 'a' -> '\u0007';
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 's' -> ' ';
                            case 't' -> '\t';
                            case 'v' -> '\u000b';
                            case '\\', '\'', '"', '$', '~', '#', ';', '{', '}', '%' -> escaped;
                            default -> throw new LibTmuxException("tmux returned an unknown option escape");
                        });
            }
        }
        return value.toString();
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

    /** Wide listings mark inherited built-ins; custom names retain every literal star. */
    private static String inherited(String name) {
        return !name.startsWith("@") && name.endsWith("*") ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Options set here and inherited built-in options, in tmux's order.
     *
     * <p>tmux's wide listing omits inherited custom options. Read one inherited custom value with
     * {@link #get(String)}.
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
     * <p>Because tmux expands the format when this is called, a {@code #(...)} in it runs a command
     * at that moment. Pass any interpolated value through {@link TmuxFormats#literal} unless you mean
     * it to be expanded.
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
