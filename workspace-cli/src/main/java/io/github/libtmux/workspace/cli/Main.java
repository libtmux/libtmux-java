package io.github.libtmux.workspace.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/** Launches the workspace application; only this entry point exits the JVM. */
public final class Main {
    private Main() {}

    /** Executes the supplied command against the invoking process environment. */
    public static void main(String[] args) {
        Thread owner = Thread.currentThread();
        var finished = new java.util.concurrent.CountDownLatch(1);
        Thread shutdown = Thread.ofPlatform().unstarted(() -> {
            owner.interrupt();
            try {
                finished.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdown);
        int status;
        try {
            status = run(args, System.getenv(), Path.of("").toAbsolutePath(), System.in, System.out, System.err);
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException shuttingDown) {
                /* The signal handler owns shutdown. */
            }
        }
        System.exit(status);
    }

    static String version() {
        return Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "development");
    }

    static boolean controllingTerminal() {
        try (var terminal = new java.io.FileInputStream("/dev/tty")) {
            return terminal.getFD().valid();
        } catch (IOException unavailable) {
            return false;
        }
    }

    @SuppressWarnings("SystemConsoleNull")
    static boolean terminal() {
        var console = System.console();
        if (console == null) return false;
        try {
            return Boolean.TRUE.equals(
                    console.getClass().getMethod("isTerminal").invoke(console));
        } catch (NoSuchMethodException olderJdk) {
            return true;
        } catch (ReflectiveOperationException inaccessible) {
            return false;
        }
    }

    record Context(
            Map<String, String> environment,
            Path directory,
            InputStream input,
            OutputStream output,
            OutputStream error) {
        Context {
            environment = Map.copyOf(environment);
            directory = directory.toAbsolutePath().normalize();
        }
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        final int status;

        Failure(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }
    }

    static Failure usage(String message) {
        return new Failure("usage", 2, message);
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Path directory,
            InputStream input,
            OutputStream output,
            OutputStream error) {
        Context context = new Context(environment, directory, input, output, error);
        boolean machine = Arrays.stream(args)
                .takeWhile(value -> !value.equals("--"))
                .anyMatch(value -> value.equals("--json") || value.equals("--ndjson"));
        try {
            CommandLine command = Arguments.create()
                    .setOut(new PrintWriter(output, true, StandardCharsets.UTF_8))
                    .setErr(new PrintWriter(error, true, StandardCharsets.UTF_8));
            ParseResult parsed = command.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parsed)) return 0;
            Reporter report = new Reporter(context, parsed);
            if (parsed.hasMatchedOption("--generate")) {
                if (parsed.hasSubcommand()) throw usage("--generate cannot accompany a command");
                if (parsed.matchedOptionValue("--generate", "schema").equals("bash")) {
                    output.write(
                            picocli.AutoComplete.bash("tmux-workspace", command).getBytes(StandardCharsets.UTF_8));
                } else report.document(Reporter.metadata(command.getCommandSpec()));
                return 0;
            }
            ParseResult leaf = parsed;
            while (leaf.hasSubcommand()) leaf = leaf.subcommand();
            String name = leaf.commandSpec().name();
            switch (name) {
                case "ls" ->
                    report.records(
                            Catalog.records(context, flag(leaf, "--full")),
                            true,
                            Catalog.directories(context),
                            flag(leaf, "--tree"));
                case "search" ->
                    report.records(
                            new Search(leaf).run(Catalog.records(context, true)),
                            false,
                            Documents.JSON.createArrayNode(),
                            false);
                case "convert", "teamocil", "tmuxinator" -> Documents.convert(context, leaf, report);
                case "load" -> Execution.load(context, leaf, report);
                case "freeze" -> Execution.freeze(context, leaf, report);
                case "edit" -> Children.edit(context, leaf, report);
                case "shell" -> Children.shell(context, leaf, report);
                case "debug-info" ->
                    report.document(Documents.JSON
                            .createObjectNode()
                            .put("port", "java")
                            .put("version", version())
                            .put("java_version", System.getProperty("java.version"))
                            .put("working_directory", Catalog.mask(context, context.directory()))
                            .set("global_workspace_dirs", Catalog.directories(context)));
                default -> throw usage("select a workspace command; use --help");
            }
            output.flush();
            return 0;
        } catch (CommandLine.ParameterException failure) {
            diagnostic(context, machine, "usage", Objects.toString(failure.getMessage(), "invalid arguments"));
            return 2;
        } catch (Failure failure) {
            diagnostic(context, machine, failure.code, Objects.toString(failure.getMessage(), "command failed"));
            return failure.status;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            diagnostic(context, machine, "interrupted", "operation interrupted");
            return 130;
        } catch (IOException
                | UncheckedIOException
                | IllegalArgumentException
                | io.github.libtmux.LibTmuxException failure) {
            diagnostic(context, machine, "invalid_config", Objects.toString(failure.getMessage(), "command failed"));
            return 1;
        }
    }

    static boolean flag(ParseResult args, String name) {
        return args.matchedOptionValue(name, false);
    }

    static void diagnostic(Context context, boolean machine, String code, String message) {
        try {
            String value = machine
                    ? Documents.JSON.writeValueAsString(
                            Documents.JSON.createObjectNode().put("code", code).put("message", message))
                    : code + ": " + Reporter.safe(message);
            context.error().write((value + "\n").getBytes(StandardCharsets.UTF_8));
            context.error().flush();
        } catch (IOException ignored) {
            // An unavailable error stream cannot carry another diagnostic.
        }
    }
}
