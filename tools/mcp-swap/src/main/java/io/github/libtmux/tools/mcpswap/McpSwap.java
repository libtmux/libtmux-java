package io.github.libtmux.tools.mcpswap;

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
            var present = exists(client.configPath());
            var backup = exists(SwapPaths.backup(client));
            var state = exists(SwapPaths.state(client));
            var recovery = backup && state ? " (swapped)" : backup || state ? " (incomplete recovery)" : "";
            var caveat = client.name().equals("pi") && !Files.isDirectory(piAdapter(context.home()))
                    ? " -- " + PI_ADAPTER_HINT
                    : "";
            context.out()
                    .printf(
                            Locale.ROOT,
                            "%-" + CLIENT_COLUMN + "s %-8s %s%s%s%n",
                            client.name(),
                            present ? "present" : "missing",
                            client.configPath(),
                            recovery,
                            caveat);
        }
        return 0;
    }

    private static int status(Arguments options, List<Client> clients, Context context) {
        var failed = false;
        for (var client : selected(options, clients)) {
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
                                    client.name(),
                                    options.name());
                } else {
                    context.out()
                            .printf(
                                    Locale.ROOT,
                                    "%-" + CLIENT_COLUMN + "s %s%n",
                                    client.name(),
                                    describe(spec.orElseThrow()));
                }
            } catch (IOException | IllegalArgumentException error) {
                context.err().println(client.name() + " unreadable: " + error.getMessage());
                failed = true;
            }
        }
        return failed ? 1 : 0;
    }

    private static int use(Arguments options, List<Client> clients, Context context)
            throws IOException, InterruptedException {
        var requested = selected(options, clients);
        var targets = new ArrayList<Client>();
        for (var client : requested) {
            if (exists(client.configPath())) {
                targets.add(client);
            } else {
                context.out().printf(Locale.ROOT, "%-" + CLIENT_COLUMN + "s skipped, no config%n", client.name());
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        var spec = launcher(options, context.repository());
        var service = new SwapService(context.home(), context.environment());
        service.use(targets, clients, options.name(), spec, true);
        context.err().println("pointing '" + options.name() + "' at: " + describe(spec));
        if (options.dryRun()) {
            for (var client : targets) {
                context.out()
                        .printf(
                                Locale.ROOT,
                                "%-" + CLIENT_COLUMN + "s would set %s = %s%n",
                                client.name(),
                                options.name(),
                                describe(spec));
            }
            return 0;
        }
        if (options.source() == Source.DIST) {
            buildDistribution(context);
        }
        service.use(targets, clients, options.name(), spec, false);
        for (var client : targets) {
            context.out().printf(Locale.ROOT, "%-" + CLIENT_COLUMN + "s set %s%n", client.name(), options.name());
        }
        return 0;
    }

    private static int revert(Arguments options, List<Client> clients, Context context) throws IOException {
        var targets = selected(options, clients).stream()
                .filter(client -> exists(SwapPaths.backup(client)) || exists(SwapPaths.state(client)))
                .toList();
        if (targets.isEmpty()) {
            for (var client : selected(options, clients)) {
                context.out().printf(Locale.ROOT, "%-" + CLIENT_COLUMN + "s nothing to revert%n", client.name());
            }
            return 0;
        }
        var service = new SwapService(context.home(), context.environment());
        service.revert(targets, clients, options.name(), options.dryRun());
        for (var client : targets) {
            context.out()
                    .printf(
                            Locale.ROOT,
                            "%-" + CLIENT_COLUMN + "s %s%n",
                            client.name(),
                            options.dryRun() ? "would restore" : "restored");
        }
        return 0;
    }

    private static int doctor(Arguments options, List<Client> clients, Context context) {
        var ready = true;
        ServerSpec spec = null;
        var gradle = context.repository().resolve("gradlew");
        if (!Files.isRegularFile(gradle) || !Files.isExecutable(gradle)) {
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
        for (var client : selected(options, clients)) {
            if (!exists(client.configPath())) {
                continue;
            }
            try {
                ConfigCodec.read(client, readConfig(client), options.name());
            } catch (IOException | IllegalArgumentException error) {
                context.out().println(client.name() + " will not parse: " + error.getMessage());
                ready = false;
            }
        }
        if (spec != null) {
            var targets = selected(options, clients).stream()
                    .filter(client -> exists(client.configPath()))
                    .toList();
            try {
                new SwapService(context.home(), context.environment())
                        .use(targets, clients, options.name(), spec, true);
            } catch (IOException | IllegalArgumentException error) {
                context.out().println("swap plan is not safe: " + detail(error));
                ready = false;
            }
        }
        if (exists(clients.getLast().configPath()) && !Files.isDirectory(piAdapter(context.home()))) {
            context.out().println("pi       " + PI_ADAPTER_HINT);
            ready = false;
        }
        context.out().println(ready ? "ready" : "not ready");
        return ready ? 0 : 1;
    }

    private static List<Client> selected(Arguments options, List<Client> clients) {
        return ClientRegistry.select(clients, options.clients());
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
                        List.of("--quiet", "--console=plain", ":libtmux-mcp:run", "--args", gradleArguments(flags)),
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
        if (command == Command.USE) {
            out.println("usage: mcp-swap use [--source dist|gradle|path] [--bin FILE]");
            out.println("                    [--socket PATH | --socket-name NAME] [--tmux FILE]");
            out.println("                    [--env KEY=VALUE] [--name NAME] [--cli CLIENT] [--dry-run]");
            return;
        }
        out.println("usage: mcp-swap <detect|status|use|revert|doctor> [options]");
        out.println("Point installed agent CLI configs at this repository's MCP build.");
    }

    @FunctionalInterface
    interface ProcessRunner {
        int run(List<String> command, Path directory) throws IOException, InterruptedException;
    }

    record Context(
            Path home,
            Map<String, String> environment,
            Path repository,
            PrintStream out,
            PrintStream err,
            ProcessRunner process) {
        Context {
            home = home.toAbsolutePath().normalize();
            environment = Map.copyOf(environment);
            repository = repository.toAbsolutePath().normalize();
        }

        static Context system() {
            return new Context(
                    Path.of(System.getProperty("user.home")),
                    System.getenv(),
                    locateRepository(),
                    System.out,
                    System.err,
                    (command, directory) -> new ProcessBuilder(command)
                            .directory(directory.toFile())
                            .inheritIO()
                            .start()
                            .waitFor());
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
            boolean dryRun,
            Source source,
            Map<String, String> environment,
            @Nullable String binary,
            @Nullable String socket,
            @Nullable String socketName,
            @Nullable String tmux) {
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
            var source = Source.DIST;
            Map<String, String> environment = new LinkedHashMap<>();
            String binary = null;
            String socket = null;
            String socketName = null;
            String tmux = null;
            var help = false;
            for (int index = 1; index < raw.length; index++) {
                var option = raw[index];
                switch (option) {
                    case "-h", "--help" -> help = true;
                    case "--dry-run" -> dryRun = true;
                    case "--name" -> name = value(raw, ++index, option);
                    case "--cli" -> clients.add(value(raw, ++index, option));
                    case "--source" -> {
                        var value = value(raw, ++index, option);
                        try {
                            source = Source.valueOf(value.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException unknown) {
                            throw new UsageException("--source must be dist, gradle, or path");
                        }
                    }
                    case "--env" -> {
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
                    case "--bin" -> binary = value(raw, ++index, option);
                    case "--socket" -> socket = value(raw, ++index, option);
                    case "--socket-name" -> socketName = value(raw, ++index, option);
                    case "--tmux" -> tmux = value(raw, ++index, option);
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
                    command, help, name, clients, dryRun, source, environment, binary, socket, socketName, tmux);
        }

        private static Arguments defaults(@Nullable Command command, boolean help) {
            return new Arguments(
                    command, help, "tmux", List.of(), false, Source.DIST, Map.of(), null, null, null, null);
        }

        private static String value(String[] raw, int index, String option) {
            if (index >= raw.length || raw[index].isBlank()) {
                throw new UsageException(option + " needs a value");
            }
            return raw[index];
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
