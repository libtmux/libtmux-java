package io.github.libtmux.junit5;

import io.github.libtmux.PaneId;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.SessionId;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.TmuxTransport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A tmux server that is not there, for testing code that uses this library without starting one.
 *
 * <p>It answers the way tmux answers, not the way a stub would: a listing is rendered from the
 * template the library sends, so a snapshot of a fake server is a real snapshot and every handle
 * works; a handle command is fenced on the server's identity and refused once the fake is replaced,
 * as tmux refuses it; a group stops at its first failure. What it models is the hierarchy —
 * sessions, windows, panes and their names and titles — and what each pane shows. What it does not
 * model is a shell: keys sent to a pane are recorded, not run, and a pane shows what the test says.
 *
 * <pre>{@code
 * FakeTmux tmux = new FakeTmux();
 * PaneId editor = tmux.addSession("work");
 * tmux.show(editor, "$ make", "make: Nothing to be done for 'all'.");
 *
 * try (Server server = tmux.server()) {
 *     Pane pane = server.pane(editor).orElseThrow();
 *     pane.capture();                 // → [$ make, make: Nothing to be done for 'all'.]
 *     pane.sendLine("make test");
 * }
 *
 * tmux.sent();                        // every command, as the library sent it
 * }</pre>
 *
 * <p>For behaviour that depends on tmux itself — what a release does with a flag, how a real shell
 * echoes — test against real tmux with {@link TmuxExtension} instead. This is for code whose
 * interest in tmux ends at the calls it makes.
 */
public final class FakeTmux implements TmuxTransport {

    /** The release it claims to be, one every supported feature is available on. */
    private static final String VERSION = "3.7c";

    private static final String LAYOUT = "c3a4,80x24,0,0,1";

    /**
     * tmux's own command table, from its source. A verb outside it is refused as tmux refuses it —
     * which matters: a handle whose server was replaced runs a deliberately unknown command, and the
     * library reads the refusal as the answer.
     */
    private static final Set<String> COMMANDS = Set.of(
            "attach-session",
            "bind-key",
            "break-pane",
            "capture-pane",
            "choose-buffer",
            "choose-client",
            "choose-tree",
            "clear-history",
            "clear-prompt-history",
            "clock-mode",
            "command-prompt",
            "confirm-before",
            "copy-mode",
            "customize-mode",
            "delete-buffer",
            "detach-client",
            "display-menu",
            "display-message",
            "display-panes",
            "display-popup",
            "find-window",
            "has-session",
            "if-shell",
            "join-pane",
            "kill-pane",
            "kill-server",
            "kill-session",
            "kill-window",
            "last-pane",
            "last-window",
            "link-window",
            "list-buffers",
            "list-clients",
            "list-commands",
            "list-keys",
            "list-panes",
            "list-sessions",
            "list-windows",
            "load-buffer",
            "lock-client",
            "lock-server",
            "lock-session",
            "move-pane",
            "move-window",
            "new-session",
            "new-window",
            "next-layout",
            "next-window",
            "paste-buffer",
            "pipe-pane",
            "previous-layout",
            "previous-window",
            "refresh-client",
            "rename-session",
            "rename-window",
            "resize-pane",
            "resize-window",
            "respawn-pane",
            "respawn-window",
            "rotate-window",
            "run-shell",
            "save-buffer",
            "select-layout",
            "select-pane",
            "select-window",
            "send-keys",
            "send-prefix",
            "server-access",
            "set-buffer",
            "set-environment",
            "set-hook",
            "set-option",
            "set-window-option",
            "show-buffer",
            "show-environment",
            "show-hooks",
            "show-messages",
            "show-options",
            "show-prompt-history",
            "show-window-options",
            "source-file",
            "split-window",
            "start-server",
            "suspend-client",
            "swap-pane",
            "swap-window",
            "switch-client",
            "unbind-key",
            "unlink-window",
            "wait-for");

    private long pid;
    // When this server started, in seconds, as #{start_time} reports it; a restart moves it on.
    private long started = 1_790_000_000L;
    private final List<FakeSession> sessions = new ArrayList<>();
    private final List<List<String>> sent = new ArrayList<>();
    private final List<FakeControlClient> controls = new ArrayList<>();
    private final Map<String, List<String>> screens = new HashMap<>();

    /** One store per scope tmux keeps: the empty key is the server's, a target names a session's. */
    private final Map<String, Map<String, Optional<String>>> environment = new LinkedHashMap<>();

    private int nextSession;
    private int nextWindow;
    private int nextPane;

    /** A fake server with no sessions, as tmux is before anything has been created. */
    public FakeTmux() {
        this(4242);
    }

    private FakeTmux(long pid) {
        this.pid = pid;
    }

    /**
     * A server this transport answers for.
     *
     * <p>Borrowed rather than owned, so closing it leaves this fake as it is and a test can inspect
     * it afterwards.
     */
    public Server server() {
        return Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("fake"))
                        .build(),
                this);
    }

    /**
     * Adds a session with one window and one pane running {@code sh}, as {@code new-session} makes.
     *
     * @return the pane, so a test can say what it shows
     */
    public synchronized PaneId addSession(String name) {
        FakeSession session = newSession(name, name, "sh", "/");
        return new PaneId(session.windows.getFirst().panes.getFirst().id);
    }

    /**
     * Replaces the server, as a restart does: a new process, and nothing in it.
     *
     * <p>Handles made before this are refused from now on, which is what a test of code that has to
     * survive a server restarting under it needs to see.
     */
    public synchronized void restart() {
        pid++;
        started++;
        controls.forEach(FakeControlClient::end);
        controls.clear();
        sessions.clear();
        screens.clear();
    }

    /** What the pane shows, one element per line, which is what capturing it answers. */
    public synchronized void show(PaneId pane, String... lines) {
        screens.put(pane.value(), List.of(lines));
    }

    /**
     * Every command the library sent, in order, each as the words tmux would have read.
     *
     * <p>Unwrapped: a command the library fenced on the server's identity or sent in a group is here
     * as itself, and the plumbing that carried it is not.
     */
    public synchronized List<List<String>> sent() {
        return List.copyOf(sent);
    }

    /** The sessions the fake holds now, by id. */
    public synchronized List<SessionId> sessions() {
        return sessions.stream().map(session -> new SessionId(session.id)).toList();
    }

    @Override
    public synchronized CommandResult execute(CommandRequest request) {
        return run(request.commands());
    }

    /**
     * Control clients of this fake: {@code server.control(session)} attaches one, its commands are
     * answered as this fake answers any other, and it hears what {@link #output} and {@link #notify}
     * push. A restart ends every client attached, as a server that went away does.
     */
    @Override
    public Optional<ControlCarrier> controlCarrier() {
        return Optional.of(command -> attach());
    }

    private synchronized Process attach() {
        FakeControlClient client = new FakeControlClient(commands -> {
            synchronized (this) {
                return run(commands);
            }
        });
        controls.add(client);
        return client;
    }

    /**
     * Pushes what a pane wrote to every attached control client, as tmux's {@code %output}: a
     * control byte or a backslash as three octal digits, and the rest as it is.
     */
    public synchronized void output(PaneId pane, String text) {
        StringBuilder line = new StringBuilder()
                .append('%')
                .append("output ")
                .append(pane.value())
                .append(' ');
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = value & 0xff;
            if (unsigned < ' ' || unsigned == '\\') {
                line.append(String.format("\\%03o", unsigned));
            } else {
                line.append((char) unsigned);
            }
        }
        push(line.toString());
    }

    /** Pushes a notification line, such as {@code %window-renamed @1 logs}, to every attached client. */
    public synchronized void notify(String line) {
        push(line);
    }

    private void push(String line) {
        controls.removeIf(client -> !client.isAlive());
        controls.forEach(client -> client.push(line));
    }

    @Override
    public void close() {}

    // ----------------------------------------------------------------------------- dispatch

    /** Runs commands in order and stops at the first failure, which is what tmux does. */
    private CommandResult run(List<List<String>> commands) {
        List<String> stdout = new ArrayList<>();
        for (List<String> argv : commands) {
            CommandResult answered = one(argv);
            stdout.addAll(answered.stdout());
            if (!answered.succeeded()) {
                return new CommandResult(answered.exitCode(), stdout, answered.stderr());
            }
        }
        return new CommandResult(0, stdout, List.of());
    }

    private CommandResult one(List<String> argv) {
        String verb = argv.getFirst();
        if (verb.equals("if-shell")) {
            return ifShell(argv);
        }
        if (verb.equals("-V")) {
            return ok("tmux " + VERSION);
        }
        if (!COMMANDS.contains(verb)) {
            return new CommandResult(1, List.of(), List.of("unknown command: " + verb));
        }
        sent.add(List.copyOf(argv));
        Flags flags = Flags.parse(argv);
        return switch (verb) {
            case "display-message" -> display(flags);
            case "list-sessions" ->
                listing(flags, sessions.stream().map(FakeTmux::first).toList());
            case "list-windows" -> listing(flags, windows(flags));
            case "list-panes" -> listing(flags, panes(flags));
            case "list-clients" -> ok();
            case "capture-pane" -> capture(flags);
            case "set-environment" -> setEnvironment(flags);
            case "show-environment" -> showEnvironment(flags);
            case "has-session" -> hasSession(flags);
            case "new-session" -> newSession(flags);
            case "new-window" -> newWindow(flags);
            case "split-window" -> split(flags);
            case "rename-session" -> rename(flags, Target::session, (session, name) -> session.name = name);
            case "rename-window" -> rename(flags, Target::window, (window, name) -> window.name = name);
            case "select-pane" -> selectPane(flags);
            case "kill-session" -> kill(flags, target -> sessions.remove(target.session()));
            case "kill-window" -> kill(flags, target -> target.session().windows.remove(target.window()));
            case "kill-pane" -> kill(flags, target -> target.window().panes.remove(target.pane()));
            case "kill-server" -> {
                sessions.clear();
                yield ok();
            }
            default -> ok();
        };
    }

    /**
     * {@code if-shell -F [-t target] condition then [else]}: the fence every handle command rides in.
     *
     * <p>Evaluated rather than assumed, so a handle made against a fake that has since been replaced
     * is refused the way tmux refuses one made against a replaced server.
     */
    private CommandResult ifShell(List<String> argv) {
        Flags flags = Flags.parse(argv);
        List<String> rest = flags.positional();
        if (!flags.has("-F") || rest.isEmpty()) {
            return ok();
        }
        Map<String, String> context =
                Target.resolve(sessions, flags.value("-t")).map(this::context).orElseGet(this::serverContext);
        boolean holds = Formats.truthy(Formats.expand(rest.get(0), context::get));
        String chosen = holds ? (rest.size() > 1 ? rest.get(1) : "") : (rest.size() > 2 ? rest.get(2) : "");
        return chosen.isEmpty() ? ok() : run(Groups.parse(chosen));
    }

    // -------------------------------------------------------------------------------- reads

    private CommandResult display(Flags flags) {
        List<String> rest = flags.positional();
        if (rest.isEmpty()) {
            return ok();
        }
        Optional<Target> target =
                flags.value("-t") == null ? Target.active(sessions) : Target.resolve(sessions, flags.value("-t"));
        Map<String, String> context = target.map(this::context).orElseGet(this::serverContext);
        return ok(Formats.expand(rest.getFirst(), context::get));
    }

    /** {@code set-environment [-gruF] [-t session] -- name [value]}. */
    private CommandResult setEnvironment(Flags flags) {
        List<String> rest = flags.positional();
        if (rest.isEmpty()) {
            return new CommandResult(1, List.of(), List.of("usage: set-environment [-gru] [-t target] name [value]"));
        }
        Map<String, Optional<String>> scope =
                environment.computeIfAbsent(environmentScope(flags), key -> new LinkedHashMap<>());
        String name = rest.getFirst();
        if (flags.has("-u")) {
            scope.remove(name);
        } else if (flags.has("-r")) {
            scope.put(name, Optional.empty());
        } else {
            String value = rest.size() > 1 ? rest.get(1) : "";
            scope.put(name, Optional.of(flags.has("-F") ? Formats.expand(value, serverContext()::get) : value));
        }
        return ok();
    }

    /**
     * {@code show-environment [-gs] [-t session] [-- name]}.
     *
     * <p>The shell form is escaped the way tmux escapes it, so a test using this double drives the
     * library's own parser rather than stepping around it — which is the point of modelling tmux
     * here instead of answering what the library hoped for. An absent name is a failure, as tmux
     * reports it, and that failure is the library's answer for "not set".
     */
    private CommandResult showEnvironment(Flags flags) {
        Map<String, Optional<String>> scope = environment.getOrDefault(environmentScope(flags), Map.of());
        List<String> wanted = flags.positional();
        if (!wanted.isEmpty()) {
            String name = wanted.getFirst();
            if (!scope.containsKey(name)) {
                return new CommandResult(1, List.of(), List.of("unknown variable: " + name));
            }
            return new CommandResult(0, asLines(reported(name, scope.get(name), flags.has("-s"))), List.of());
        }
        return new CommandResult(
                0,
                scope.entrySet().stream()
                        .map(held -> reported(held.getKey(), held.getValue(), flags.has("-s")))
                        .flatMap(line -> asLines(line).stream())
                        .toList(),
                List.of());
    }

    /**
     * A value's own newlines are newlines on the wire, so a multi-line value really does arrive as
     * several lines. Handing it back as one string would let the library's parser close a value at
     * the first terminator it saw and still look right, which is the mistake this double exists to
     * catch rather than hide.
     */
    private static List<String> asLines(String reported) {
        return List.of(reported.split("\n", -1));
    }

    /** The server's store unless a session is named without {@code -g}. */
    private static String environmentScope(Flags flags) {
        String session = flags.value("-t");
        return session == null || flags.has("-g") ? "" : session;
    }

    private static String reported(String name, Optional<String> value, boolean shell) {
        if (value.isEmpty()) {
            return shell ? "unset " + name + ";" : "-" + name;
        }
        if (!shell) {
            return name + "=" + value.get();
        }
        return name + "=\"" + escapedForShell(value.get()) + "\"; export " + name + ";";
    }

    /** tmux escapes exactly {@code $}, a backtick, {@code "} and {@code \\}, each with one backslash. */
    private static String escapedForShell(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '$' || character == '`' || character == '"' || character == '\\') {
                out.append('\\');
            }
            out.append(character);
        }
        return out.toString();
    }

    private CommandResult listing(Flags flags, List<Target> rows) {
        String template = flags.value("-F");
        if (template == null) {
            return ok();
        }
        String filter = flags.value("-f");
        return new CommandResult(
                0,
                rows.stream()
                        .map(this::scope)
                        .filter(row -> filter == null || Formats.truthy(Formats.expand(filter, row)))
                        .map(row -> Formats.expand(template, row))
                        .toList(),
                List.of());
    }

    /** A row's variables, and the windows and panes a {@code W:} or {@code P:} loop visits from it. */
    private Formats.Scope scope(Target target) {
        Map<String, String> values = context(target);
        return new Formats.Scope() {
            @Override
            public @Nullable String get(String variable) {
                return values.get(variable);
            }

            @Override
            public List<Formats.Scope> windows() {
                return target.session().windows.stream()
                        .map(window -> scope(new Target(target.session(), window, window.active())))
                        .toList();
            }

            @Override
            public List<Formats.Scope> panes() {
                return target.window().panes.stream()
                        .map(pane -> scope(new Target(target.session(), target.window(), pane)))
                        .toList();
            }
        };
    }

    private static Target first(FakeSession session) {
        return new Target(
                session, session.windows.getFirst(), session.windows.getFirst().active());
    }

    private List<Target> windows(Flags flags) {
        List<Target> rows = new ArrayList<>();
        for (FakeSession session : sessions) {
            if (!flags.has("-a") && !isTarget(flags, session)) {
                continue;
            }
            for (FakeWindow window : session.windows) {
                rows.add(new Target(session, window, window.active()));
            }
        }
        return rows;
    }

    /** Every pane with {@code -a}, as the library's own listings ask; otherwise the target window's. */
    private List<Target> panes(Flags flags) {
        Optional<Target> target = flags.has("-a")
                ? Optional.empty()
                : Target.resolve(sessions, flags.value("-t")).or(() -> Target.active(sessions));
        List<Target> rows = new ArrayList<>();
        for (FakeSession session : sessions) {
            for (FakeWindow window : session.windows) {
                if (target.isPresent() && target.get().window() != window) {
                    continue;
                }
                for (FakePane pane : window.panes) {
                    rows.add(new Target(session, window, pane));
                }
            }
        }
        return rows;
    }

    private boolean isTarget(Flags flags, FakeSession session) {
        return Target.resolve(sessions, flags.value("-t"))
                .map(target -> target.session() == session)
                .orElse(false);
    }

    private CommandResult capture(Flags flags) {
        return Target.resolve(sessions, flags.value("-t"))
                .map(target ->
                        ok(screens.getOrDefault(target.pane().id, List.of()).toArray(String[]::new)))
                .orElseGet(() -> missing(flags.value("-t")));
    }

    private CommandResult hasSession(Flags flags) {
        return Target.resolve(sessions, flags.value("-t")).isPresent() ? ok() : missing(flags.value("-t"));
    }

    // ------------------------------------------------------------------------------ changes

    private CommandResult newSession(Flags flags) {
        String name = Formats.literal(Optional.ofNullable(flags.value("-s")).orElse(Integer.toString(nextSession)));
        String window = Formats.literal(Optional.ofNullable(flags.value("-n")).orElse("sh"));
        FakeSession session = newSession(name, window, command(flags), directory(flags));
        return created(
                flags,
                context(new Target(
                        session,
                        session.windows.getFirst(),
                        session.windows.getFirst().active())));
    }

    private CommandResult newWindow(Flags flags) {
        Optional<Target> target = Target.resolve(sessions, flags.value("-t"));
        if (target.isEmpty()) {
            return missing(flags.value("-t"));
        }
        FakeSession session = target.get().session();
        String name = Formats.literal(Optional.ofNullable(flags.value("-n")).orElse(command(flags)));
        FakeWindow window = newWindow(session, name, command(flags), directory(flags));
        return created(flags, context(new Target(session, window, window.active())));
    }

    private CommandResult split(Flags flags) {
        Optional<Target> target =
                flags.value("-t") == null ? Target.active(sessions) : Target.resolve(sessions, flags.value("-t"));
        if (target.isEmpty()) {
            return missing(flags.value("-t"));
        }
        FakeWindow window = target.get().window();
        FakePane pane =
                new FakePane("%" + nextPane++, window.panes.size(), command(flags), directory(flags), 1000 + nextPane);
        window.panes.add(pane);
        return created(flags, context(new Target(target.get().session(), window, pane)));
    }

    private <T> CommandResult rename(
            Flags flags, Function<Target, T> part, java.util.function.BiConsumer<T, String> setter) {
        Optional<Target> target = Target.resolve(sessions, flags.value("-t"));
        List<String> rest = flags.positional();
        if (target.isEmpty() || rest.isEmpty()) {
            return missing(flags.value("-t"));
        }
        setter.accept(part.apply(target.get()), rest.getFirst());
        return ok();
    }

    private CommandResult selectPane(Flags flags) {
        Optional<Target> target = Target.resolve(sessions, flags.value("-t"));
        if (target.isEmpty()) {
            return missing(flags.value("-t"));
        }
        String title = flags.value("-T");
        if (title != null) {
            target.get().pane().title = Formats.literal(title);
        }
        return ok();
    }

    private CommandResult kill(Flags flags, java.util.function.Consumer<Target> remove) {
        Optional<Target> target = Target.resolve(sessions, flags.value("-t"));
        if (target.isEmpty()) {
            return missing(flags.value("-t"));
        }
        remove.accept(target.get());
        sessions.forEach(session -> session.windows.removeIf(window -> window.panes.isEmpty()));
        sessions.removeIf(session -> session.windows.isEmpty());
        return ok();
    }

    private CommandResult created(Flags flags, Map<String, String> context) {
        String format = flags.value("-F");
        return flags.has("-P") && format != null ? ok(Formats.expand(format, context::get)) : ok();
    }

    private FakeSession newSession(String name, String windowName, String command, String directory) {
        FakeSession session = new FakeSession("$" + nextSession++, name);
        sessions.add(session);
        newWindow(session, windowName, command, directory);
        return session;
    }

    private FakeWindow newWindow(FakeSession session, String name, String command, String directory) {
        FakeWindow window = new FakeWindow("@" + nextWindow++, session.windows.size(), name);
        session.windows.forEach(other -> other.active = false);
        window.active = true;
        window.panes.add(new FakePane("%" + nextPane++, 0, command, directory, 1000 + nextPane));
        session.windows.add(window);
        return window;
    }

    private static String command(Flags flags) {
        List<String> rest = flags.positional();
        if (rest.isEmpty()) {
            return "sh";
        }
        String program = rest.getFirst();
        return program.substring(program.lastIndexOf('/') + 1);
    }

    private static String directory(Flags flags) {
        return Formats.literal(Optional.ofNullable(flags.value("-c")).orElse("/"));
    }

    // ------------------------------------------------------------------------------ formats

    private Map<String, String> serverContext() {
        Map<String, String> context = new HashMap<>();
        context.put("pid", Long.toString(pid));
        context.put("version", VERSION);
        context.put("start_time", Long.toString(started));
        context.put("socket_path", "/tmp/libtmux-java-fake/" + pid);
        return context;
    }

    /** Every format variable the library asks for, for one pane in its window in its session. */
    private Map<String, String> context(Target target) {
        Map<String, String> context = serverContext();
        FakeSession session = target.session();
        FakeWindow window = target.window();
        FakePane pane = target.pane();
        context.put("session_id", session.id);
        context.put("session_name", session.name);
        context.put("session_attached", "0");
        context.put("session_windows", Integer.toString(session.windows.size()));
        context.put("window_id", window.id);
        context.put("window_index", Integer.toString(window.index));
        context.put("window_name", window.name);
        context.put("window_active", window.active ? "1" : "0");
        context.put("window_panes", Integer.toString(window.panes.size()));
        context.put("window_linked", "0");
        context.put("window_width", "80");
        context.put("window_height", "24");
        context.put("window_layout", LAYOUT);
        context.put("pane_id", pane.id);
        context.put("pane_index", Integer.toString(pane.index));
        context.put("pane_active", pane == window.active() ? "1" : "0");
        context.put("pane_current_command", pane.command);
        context.put("pane_width", "80");
        context.put("pane_height", "24");
        context.put("pane_left", "0");
        context.put("pane_top", "0");
        context.put("pane_title", pane.title);
        context.put("pane_current_path", pane.directory);
        context.put("pane_pid", Long.toString(pane.pid));
        context.put("pane_at_top", "1");
        context.put("pane_at_bottom", "1");
        context.put("pane_at_left", "1");
        context.put("pane_at_right", "1");
        context.put("pane_floating_flag", "0");
        context.put("pane_dead", "0");
        context.put("pane_mode", "");
        return context;
    }

    private static CommandResult ok(String... lines) {
        return new CommandResult(0, List.of(lines), List.of());
    }

    private static CommandResult missing(@Nullable String target) {
        return new CommandResult(
                1, List.of(), List.of("can't find session: " + (target == null ? "" : target.replaceFirst("^=", ""))));
    }
}
