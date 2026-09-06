package io.github.libtmux.tools.mcpswap;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Internal command-line entry point for swapping agent MCP configurations. */
public final class McpSwap {
    private static final int CLIENT_COLUMN = 9;
    private static final String PI_ADAPTER_HINT = "needs the pi-mcp-adapter; Pi has no built-in MCP client";
    private static final Map<String, String> AUTH_ENVIRONMENT = Map.of(
            "ANTHROPIC_API_KEY", "claude",
            "OPENAI_API_KEY", "codex",
            "GEMINI_API_KEY", "gemini",
            "GOOGLE_API_KEY", "gemini",
            "XAI_API_KEY", "grok",
            "GROK_API_KEY", "grok");

    private McpSwap() {}

    /** Run the command-line utility. */
    public static void main(String[] arguments) {
        System.exit(run(arguments, Context.system()));
    }

    static int run(String[] arguments, Context context) {
        try {
            var options = Arguments.parse(arguments);
            if (options.help()) {
                printHelp(context.out(), options.command());
                return 0;
            }
            var clients = ClientRegistry.knownClients(context.home(), context.environment());
            return switch (Objects.requireNonNull(options.command())) {
                case DETECT -> detect(clients, context);
                case STATUS -> status(options, clients, context);
                case USE -> use(options, clients, context);
                case REVERT -> revert(options, clients, context);
                case DOCTOR -> doctor(options, clients, context);
            };
        } catch (UsageException error) {
            context.err().println("mcp-swap: " + error.getMessage());
            context.err().println("Try 'mcp-swap --help'.");
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            context.err().println("mcp-swap: interrupted");
            return 130;
        } catch (IOException | IllegalArgumentException error) {
            context.err().println("mcp-swap: " + detail(error));
            return 1;
        }
    }

    private static int detect(List<Client> clients, Context context) {
        for (var client : clients) {
            var config = exists(client.configPath());
            var binary = executable(client, context.environment()) != null;
            var targets = ClientRegistry.allScopes(List.of(client), context.repository());
            var swapped = targets.stream()
                    .anyMatch(target -> exists(SwapPaths.backup(target)) && exists(SwapPaths.state(target)));
            var incomplete = targets.stream()
                    .anyMatch(target -> exists(SwapPaths.backup(target)) != exists(SwapPaths.state(target)));
            var recovery = incomplete ? " (incomplete recovery)" : swapped ? " (swapped)" : "";
            var caveat = client.name().equals("pi") && !Files.isDirectory(piAdapter(context.home()))
                    ? " -- " + PI_ADAPTER_HINT
                    : "";
            List<String> missing = new ArrayList<>();
            if (!binary) {
                missing.add("binary missing");
            }
            if (!config) {
                missing.add("config missing: " + client.configPath());
            }
            var detail = missing.isEmpty() ? "" : " (" + String.join(", ", missing) + ")";
            context.out()
                    .printf(
                            Locale.ROOT,
                            "[%3s] %-" + CLIENT_COLUMN + "s%s%s%s%n",
                            binary && config ? "yes" : "no",
                            client.name(),
                            detail,
                            recovery,
                            caveat);
        }
        return 0;
    }

    private static int status(Arguments options, List<Client> clients, Context context) {
        var failed = false;
        for (var client : statusTargets(options, clients, context)) {
            if (!exists(client.configPath())) {
                continue;
            }
            try {
                var spec = ConfigCodec.read(client, readConfig(client), options.name());
                if (spec.isEmpty()) {
                    context.out()
                            .printf(
                                    Locale.ROOT,
                                    "%-" + CLIENT_COLUMN + "s no '%s' server%n",
                                    client.label(),
                                    options.name());
                } else {
                    context.out()
                            .printf(
                                    Locale.ROOT,
                                    "%-" + CLIENT_COLUMN + "s %s%n",
                                    client.label(),
                                    describe(spec.orElseThrow()));
                }
            } catch (IOException | IllegalArgumentException error) {
                context.err().println(client.label() + " unreadable: " + error.getMessage());
                failed = true;
            }
        }
        return failed ? 1 : 0;
    }

    private static int use(Arguments options, List<Client> clients, Context context)
            throws IOException, InterruptedException {
        var requested = scoped(
                defaultSelected(options, clients, context),
                options.scope() == null ? Scope.PROJECT : options.scope(),
                context.repository());
        var targets = new ArrayList<Client>();
        for (var client : requested) {
            if (exists(client.configPath())) {
                targets.add(client);
            } else {
                context.out().printf(Locale.ROOT, "%-" + CLIENT_COLUMN + "s skipped, no config%n", client.label());
            }
        }
        if (targets.isEmpty()) {
            context.err().println("no CLIs detected; nothing to do");
            return 1;
        }
        var spec = launcher(options, context.repository());
        var service = new SwapService(context.home(), context.environment());
        var allTargets = ClientRegistry.allScopes(clients, context.repository());
        var preview = service.previewUse(targets, allTargets, options.name(), spec);
        context.err().println("pointing '" + options.name() + "' at: " + describe(spec));
        if (options.dryRun()) {
            for (var planned : preview) {
                context.out()
                        .printf(
                                Locale.ROOT,
                                "%-" + CLIENT_COLUMN + "s would set %s = %s%n",
                                planned.label(),
                                options.name(),
                                describe(planned.spec()));
            }
            return 0;
        }
        if (options.source() == Source.DIST) {
            buildDistribution(context);
        }
        List<SwapService.PlannedSpec> preflighted = null;
        if (!options.noPreflight()) {
            preflighted = service.previewUse(targets, allTargets, options.name(), spec);
            for (var planned : preflighted) {
                context.err().println("preflight: [" + planned.label() + "] " + describe(planned.spec()));
                context.preflight().run(planned.spec());
            }
        }
        service.use(targets, allTargets, options.name(), spec, false, preflighted);
        for (var client : targets) {
            context.out().printf(Locale.ROOT, "%-" + CLIENT_COLUMN + "s set %s%n", client.label(), options.name());
        }
        return 0;
    }

    private static int revert(Arguments options, List<Client> clients, Context context) throws IOException {
        var selected = revertSelected(options, clients, context);
        var targets = revertTargets(options, selected, context.repository()).stream()
                .filter(client -> exists(SwapPaths.backup(client)) || exists(SwapPaths.state(client)))
                .toList();
        if (targets.isEmpty()) {
            for (var client : selected) {
                context.out().printf(Locale.ROOT, "%-" + CLIENT_COLUMN + "s nothing to revert%n", client.name());
            }
            return 0;
        }
        var service = new SwapService(context.home(), context.environment());
        service.revert(
                targets, ClientRegistry.allScopes(clients, context.repository()), options.name(), options.dryRun());
        for (var client : targets) {
            context.out()
                    .printf(
                            Locale.ROOT,
                            "%-" + CLIENT_COLUMN + "s %s%n",
                            client.label(),
                            options.dryRun() ? "would restore" : "restored");
        }
        return 0;
    }

    private static int doctor(Arguments options, List<Client> clients, Context context) {
        var ready = true;
        ServerSpec spec = null;
        var chosen = selected(options, clients);
        var allTargets = ClientRegistry.allScopes(clients, context.repository());
        var gradle = context.repository().resolve("gradlew");
        if (options.source() != Source.PATH && (!Files.isRegularFile(gradle) || !Files.isExecutable(gradle))) {
            context.out().println("no executable gradlew: is this the repository root?");
            ready = false;
        }
        try {
            spec = launcher(options, context.repository());
        } catch (UsageException error) {
            context.out().println(error.getMessage());
            ready = false;
        }
        if (options.source() == Source.DIST && !Files.isExecutable(distributionLauncher(context.repository()))) {
            context.out().println("no built MCP launcher; run './gradlew :libtmux-mcp:installDist'");
            ready = false;
        }
        for (var client : chosen) {
            if (!exists(client.configPath())) {
                continue;
            }
            if (executable(client, context.environment()) == null) {
                context.out().println(client.name() + " binary missing from PATH");
            }
        }
        for (var client : ClientRegistry.allScopes(chosen, context.repository())) {
            if (!exists(client.configPath())) {
                continue;
            }
            try {
                ConfigCodec.read(client, readConfig(client), options.name());
            } catch (IOException | IllegalArgumentException error) {
                context.out().println(client.label() + " will not parse: " + error.getMessage());
                ready = false;
            }
        }
        if (spec != null) {
            var targets = scoped(chosen, Scope.PROJECT, context.repository()).stream()
                    .filter(client -> exists(client.configPath()))
                    .toList();
            try {
                new SwapService(context.home(), context.environment())
                        .previewUse(targets, allTargets, options.name(), spec);
            } catch (IOException | IllegalArgumentException error) {
                context.out().println("swap plan is not safe: " + detail(error));
                ready = false;
            }
        }
        for (var target : allTargets) {
            if (!chosen.stream().anyMatch(client -> client.name().equals(target.name()))) {
                continue;
            }
            var state = exists(SwapPaths.state(target));
            var backup = exists(SwapPaths.backup(target));
            if (state && backup) {
                context.out().println("outstanding swap: " + target.label());
            } else if (state || backup) {
                context.out().println("incomplete recovery: " + target.label());
                ready = false;
            }
        }
        for (var entry : AUTH_ENVIRONMENT.entrySet()) {
            if (context.environment().containsKey(entry.getKey())) {
                context.out().println(entry.getKey() + " overrides " + entry.getValue() + " stored login");
            }
        }
        if (chosen.contains(clients.getLast())
                && exists(clients.getLast().configPath())
                && !Files.isDirectory(piAdapter(context.home()))) {
            context.out().println("pi       " + PI_ADAPTER_HINT);
            ready = false;
        }
        context.out().println(ready ? "ready" : "not ready");
        return ready ? 0 : 1;
    }

    private static List<Client> selected(Arguments options, List<Client> clients) {
        return ClientRegistry.select(clients, options.clients());
    }

    private static List<Client> defaultSelected(Arguments options, List<Client> clients, Context context) {
        if (!options.clients().isEmpty()) {
            return selected(options, clients);
        }
        return clients.stream()
                .filter(client -> exists(client.configPath()) && executable(client, context.environment()) != null)
                .toList();
    }

    private static List<Client> statusTargets(Arguments options, List<Client> clients, Context context) {
        var selected = defaultSelected(options, clients, context);
        return selected.stream()
                .flatMap(client -> {
                    if (!client.name().equals("claude")) {
                        return java.util.stream.Stream.of(client.scoped(Scope.USER, context.repository()));
                    }
                    if (options.scope() != null) {
                        return java.util.stream.Stream.of(client.scoped(options.scope(), context.repository()));
                    }
                    return java.util.stream.Stream.of(
                            client.scoped(Scope.USER, context.repository()),
                            client.scoped(Scope.PROJECT, context.repository()));
                })
                .toList();
    }

    private static List<Client> revertSelected(Arguments options, List<Client> clients, Context context) {
        if (!options.clients().isEmpty()) {
            return selected(options, clients);
        }
        return clients.stream()
                .filter(client -> ClientRegistry.allScopes(List.of(client), context.repository()).stream()
                        .anyMatch(target -> exists(SwapPaths.state(target)) || exists(SwapPaths.backup(target))))
                .toList();
    }

    private static List<Client> revertTargets(Arguments options, List<Client> clients, Path repository) {
        return clients.stream()
                .flatMap(client -> {
                    if (!client.name().equals("claude")) {
                        return java.util.stream.Stream.of(client.scoped(Scope.USER, repository));
                    }
                    if (options.scope() != null) {
                        return java.util.stream.Stream.of(client.scoped(options.scope(), repository));
                    }
                    return java.util.stream.Stream.of(
                            client.scoped(Scope.USER, repository), client.scoped(Scope.PROJECT, repository));
                })
                .toList();
    }

    private static List<Client> scoped(List<Client> clients, Scope scope, Path repository) {
        return ClientRegistry.scoped(clients, scope, repository);
    }

    private static @Nullable Path executable(Client client, Map<String, String> environment) {
        var raw = environment.getOrDefault("PATH", "");
        for (var directory : raw.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (directory.isBlank()) {
                continue;
            }
            var candidate = Path.of(directory).resolve(client.binary());
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static ServerSpec launcher(Arguments options, Path repository) {
        List<String> flags = new ArrayList<>();
        addFlag(flags, "--socket", options.socket());
        addFlag(flags, "--socket-name", options.socketName());
        addFlag(flags, "--tmux", options.tmux());
        return switch (options.source()) {
            case DIST -> new ServerSpec(distributionLauncher(repository).toString(), flags, options.environment());
            case GRADLE -> {
                var gradle = repository.resolve("gradlew").toAbsolutePath().normalize();
                if (!Files.isRegularFile(gradle) || !Files.isExecutable(gradle)) {
                    throw new UsageException("--source gradle needs an executable gradlew");
                }
                yield new ServerSpec(
                        gradle.toString(),
                        List.of(
                                "--quiet",
                                "--console=plain",
                                "--no-daemon",
                                "--max-workers=5",
                                ":libtmux-mcp:run",
                                "--args",
                                gradleArguments(flags)),
                        options.environment());
            }
            case PATH -> {
                if (options.binary() == null) {
                    throw new UsageException("--source path needs --bin");
                }
                var candidate = Path.of(options.binary());
                var binary = (candidate.isAbsolute() ? candidate : repository.resolve(candidate))
                        .toAbsolutePath()
                        .normalize();
                if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
                    throw new UsageException("--bin must name an executable file: " + binary);
                }
                yield new ServerSpec(binary.toString(), flags, options.environment());
            }
        };
    }

    private static void buildDistribution(Context context) throws IOException, InterruptedException {
        var command = List.of(
                context.repository().resolve("gradlew").toString(),
                "--quiet",
                "--console=plain",
                "--max-workers=5",
                ":libtmux-mcp:installDist");
        context.err().println("building :libtmux-mcp:installDist ...");
        var status = context.process().run(command, context.repository());
        if (status != 0) {
            throw new IOException("Gradle installDist failed with status " + status);
        }
        var launcher = distributionLauncher(context.repository());
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
            throw new IOException("installDist did not write an executable launcher: " + launcher);
        }
    }

    private static Path distributionLauncher(Path repository) {
        return repository
                .resolve("libtmux-mcp/build/install/libtmux-mcp/bin/libtmux-mcp")
                .toAbsolutePath()
                .normalize();
    }

    private static Path piAdapter(Path home) {
        return home.resolve(".pi/agent/npm/node_modules/pi-mcp-adapter");
    }

    private static byte[] readConfig(Client client) throws IOException {
        var route = PathRoute.inspect(client.configPath());
        return FileSnapshot.capture(route.target()).bytes();
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void addFlag(List<String> flags, String name, @Nullable String value) {
        if (value != null) {
            flags.add(name);
            flags.add(value);
        }
    }

    private static String gradleArguments(List<String> arguments) {
        return arguments.stream().map(McpSwap::quoteArgument).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quoteArgument(String argument) {
        if (argument.matches("[A-Za-z0-9_./:@%+=,-]+")) {
            return argument;
        }
        return '"' + argument.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String describe(ServerSpec spec) {
        return String.join(
                " ",
                java.util.stream.Stream.concat(java.util.stream.Stream.of(spec.command()), spec.arguments().stream())
                        .toList());
    }

    private static String detail(Throwable error) {
        var message = String.valueOf(error.getMessage());
        var cause = error.getCause();
        while (cause != null) {
            var next = String.valueOf(cause.getMessage());
            if (!next.equals(message)) {
                message += ": " + next;
            }
            cause = cause.getCause();
        }
        return message;
    }

    private static void printHelp(PrintStream out, @Nullable Command command) {
        if (command == null) {
            out.println("usage: mcp-swap <detect|status|use|revert|doctor> [options]");
            out.println("Point installed agent CLI configs at this repository's MCP build.");
        } else {
            switch (command) {
                case DETECT -> out.println("usage: mcp-swap detect");
                case STATUS -> out.println("usage: mcp-swap status [--name NAME] [--cli CLIENT] [--scope SCOPE]");
                case USE -> {
                    out.println("usage: mcp-swap use [--source dist|gradle|path] [--bin FILE]");
                    out.println("                    [--socket PATH | --socket-name NAME] [--tmux FILE]");
                    out.println("                    [--env KEY=VALUE] [--name NAME] [--cli CLIENT] [--scope SCOPE]");
                    out.println("                    [--dry-run] [--no-preflight]");
                }
                case REVERT ->
                    out.println("usage: mcp-swap revert [--name NAME] [--cli CLIENT] [--scope SCOPE] [--dry-run]");
                case DOCTOR -> {
                    out.println("usage: mcp-swap doctor [--source dist|gradle|path] [--bin FILE]");
                    out.println("                       [--socket PATH | --socket-name NAME] [--tmux FILE]");
                    out.println("                       [--env KEY=VALUE] [--name NAME] [--cli CLIENT]");
                }
            }
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        int run(List<String> command, Path directory) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface PreflightRunner {
        void run(ServerSpec spec) throws IOException, InterruptedException;
    }

    record Context(
            Path home,
            Map<String, String> environment,
            Path repository,
            PrintStream out,
            PrintStream err,
            ProcessRunner process,
            PreflightRunner preflight) {
        Context {
            home = home.toAbsolutePath().normalize();
            environment = Map.copyOf(environment);
            repository = repository.toAbsolutePath().normalize();
        }

        static Context system() {
            var repository = locateRepository();
            return new Context(
                    Path.of(System.getProperty("user.home")),
                    System.getenv(),
                    repository,
                    System.out,
                    System.err,
                    (command, directory) -> new ProcessBuilder(command)
                            .directory(directory.toFile())
                            .inheritIO()
                            .start()
                            .waitFor(),
                    spec -> McpPreflight.run(spec, System.getenv(), repository));
        }
    }

    private enum Command {
        DETECT,
        STATUS,
        USE,
        REVERT,
        DOCTOR
    }

    private enum Source {
        DIST,
        GRADLE,
        PATH
    }

    private record Arguments(
            @Nullable Command command,
            boolean help,
            String name,
            List<String> clients,
            @Nullable Scope scope,
            boolean dryRun,
            Source source,
            Map<String, String> environment,
            @Nullable String binary,
            @Nullable String socket,
            @Nullable String socketName,
            @Nullable String tmux,
            boolean noPreflight) {
        Arguments {
            clients = List.copyOf(clients);
            environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        }

        static Arguments parse(String[] raw) {
            if (raw.length == 0 || (raw.length == 1 && (raw[0].equals("--help") || raw[0].equals("-h")))) {
                return defaults(null, true);
            }
            final Command command;
            try {
                command = Command.valueOf(raw[0].replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new UsageException("unknown command: " + raw[0]);
            }
            var name = "tmux";
            List<String> clients = new ArrayList<>();
            var dryRun = false;
            Scope scope = null;
            var source = Source.DIST;
            Map<String, String> environment = new LinkedHashMap<>();
            String binary = null;
            String socket = null;
            String socketName = null;
            String tmux = null;
            var help = false;
            var noPreflight = false;
            for (int index = 1; index < raw.length; index++) {
                var option = raw[index];
                switch (option) {
                    case "-h", "--help" -> help = true;
                    case "--dry-run" -> {
                        requireOption(command, option, Command.USE, Command.REVERT);
                        dryRun = true;
                    }
                    case "--no-preflight" -> {
                        requireOption(command, option, Command.USE);
                        noPreflight = true;
                    }
                    case "--name" -> {
                        requireOption(command, option, Command.STATUS, Command.USE, Command.REVERT, Command.DOCTOR);
                        name = value(raw, ++index, option);
                    }
                    case "--cli" -> {
                        requireOption(command, option, Command.STATUS, Command.USE, Command.REVERT, Command.DOCTOR);
                        clients.add(value(raw, ++index, option));
                    }
                    case "--scope" -> {
                        requireOption(command, option, Command.STATUS, Command.USE, Command.REVERT);
                        try {
                            scope = Scope.parse(value(raw, ++index, option));
                        } catch (IllegalArgumentException error) {
                            throw new UsageException(String.valueOf(error.getMessage()));
                        }
                    }
                    case "--source" -> {
                        requireOption(command, option, Command.USE, Command.DOCTOR);
                        var value = value(raw, ++index, option);
                        try {
                            source = Source.valueOf(value.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException unknown) {
                            throw new UsageException("--source must be dist, gradle, or path");
                        }
                    }
                    case "--env" -> {
                        requireOption(command, option, Command.USE, Command.DOCTOR);
                        var value = value(raw, ++index, option);
                        var separator = value.indexOf('=');
                        if (separator < 1 || !value.substring(0, separator).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                            throw new UsageException("--env expects KEY=VALUE");
                        }
                        var key = value.substring(0, separator);
                        if (key.equals("LIBTMUX_SAFETY")) {
                            throw new UsageException("LIBTMUX_SAFETY is retired; use LIBTMUX_TOOLSETS");
                        }
                        environment.put(key, value.substring(separator + 1));
                    }
                    case "--bin" -> {
                        requireOption(command, option, Command.USE, Command.DOCTOR);
                        binary = value(raw, ++index, option);
                    }
                    case "--socket" -> {
                        requireOption(command, option, Command.USE, Command.DOCTOR);
                        socket = value(raw, ++index, option);
                    }
                    case "--socket-name" -> {
                        requireOption(command, option, Command.USE, Command.DOCTOR);
                        socketName = value(raw, ++index, option);
                    }
                    case "--tmux" -> {
                        requireOption(command, option, Command.USE, Command.DOCTOR);
                        tmux = value(raw, ++index, option);
                    }
                    case "--safety", "--watch" ->
                        throw new UsageException(
                                "LIBTMUX_SAFETY and the old safety/watch controls are retired; use LIBTMUX_TOOLSETS");
                    default -> throw new UsageException("unknown option: " + option);
                }
            }
            if (socket != null && socketName != null) {
                throw new UsageException("--socket and --socket-name are mutually exclusive");
            }
            if (source == Source.PATH && binary == null) {
                throw new UsageException("--source path needs --bin");
            }
            if (binary != null && source != Source.PATH) {
                throw new UsageException("--bin requires --source path");
            }
            if ((command == Command.DETECT || command == Command.STATUS || command == Command.REVERT)
                    && (source != Source.DIST
                            || !environment.isEmpty()
                            || binary != null
                            || socket != null
                            || socketName != null
                            || tmux != null)) {
                throw new UsageException("launcher options apply only to use or doctor");
            }
            return new Arguments(
                    command,
                    help,
                    name,
                    clients,
                    scope,
                    dryRun,
                    source,
                    environment,
                    binary,
                    socket,
                    socketName,
                    tmux,
                    noPreflight);
        }

        private static Arguments defaults(@Nullable Command command, boolean help) {
            return new Arguments(
                    command,
                    help,
                    "tmux",
                    List.of(),
                    null,
                    false,
                    Source.DIST,
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    false);
        }

        private static String value(String[] raw, int index, String option) {
            if (index >= raw.length || raw[index].isBlank()) {
                throw new UsageException(option + " needs a value");
            }
            return raw[index];
        }

        private static void requireOption(Command command, String option, Command... allowed) {
            if (java.util.Arrays.stream(allowed).noneMatch(command::equals)) {
                throw new UsageException(
                        option + " does not apply to " + command.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static Path locateRepository() {
        var candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("gradlew"))
                    && Files.isDirectory(candidate.resolve("libtmux-mcp"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static final class UsageException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
