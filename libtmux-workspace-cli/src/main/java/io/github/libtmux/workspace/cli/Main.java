package io.github.libtmux.workspace.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
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
            OutputStream error,
            boolean processError) {
        Context(
                Map<String, String> environment,
                Path directory,
                InputStream input,
                OutputStream output,
                OutputStream error) {
            this(environment, directory, input, output, error, false);
        }

        Context {
            environment = Map.copyOf(environment);
            directory = directory.toAbsolutePath().normalize();
        }
    }

    static class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Machine.Code code;
        final int status;

        Failure(Machine.Code code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }
    }

    /**
     * Nothing on PATH by that name.
     *
     * <p>Its own type because what it means depends on which program was looked for: a missing tmux
     * is not the same answer as a missing editor, and the shared code set has one name for both.
     */
    static final class Missing extends Failure {
        private static final long serialVersionUID = 1L;

        Missing(String message) {
            super(Machine.Code.SCRIPT_FAILED, 1, message);
        }
    }

    static Failure usage(String message) {
        return new Failure(Machine.Code.USAGE, 2, message);
    }

    /**
     * The one place a failure becomes a machine code, used by the top-level diagnostic and by any
     * command that writes a failure into its own envelope, so the two cannot drift.
     */
    static Machine.Code classify(Throwable failure) {
        if (failure instanceof InterruptedException || failure instanceof InterruptedIOException)
            return Machine.Code.INTERRUPTED;
        if (failure instanceof Failure known) return known.code;
        if (failure instanceof io.github.libtmux.exception.LibTmuxException) return Machine.Code.TMUX_FAILED;
        if (failure instanceof IOException
                || failure instanceof UncheckedIOException
                || failure instanceof IllegalArgumentException) return Machine.Code.INVALID_WORKSPACE;
        return Machine.Code.TMUX_FAILED;
    }

    /** The exit status that goes with a classified failure. */
    static int status(Throwable failure, Machine.Code code) {
        if (failure instanceof Failure known) return known.status;
        return code == Machine.Code.INTERRUPTED ? 130 : 1;
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            Path directory,
            InputStream input,
            OutputStream output,
            OutputStream error) {
        try (var streams = new BorrowedOutput(output, error)) {
            Context context = new Context(
                    environment, directory, input, streams.output(), streams.error(), System.err.equals(error));
            int status = execute(args, context);
            if (streams.interrupted()) {
                Thread.currentThread().interrupt();
                return 130;
            }
            return status;
        }
    }

    private static int execute(String[] args, Context context) {
        OutputStream output = context.output();
        OutputStream error = context.error();
        boolean machine = Arrays.stream(args)
                .takeWhile(value -> !value.equals("--"))
                .anyMatch(value -> value.equals("--json") || value.equals("--ndjson"));
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("operation interrupted");
            CommandLine command = Arguments.create()
                    .setOut(new PrintWriter(output, true, StandardCharsets.UTF_8))
                    .setErr(new PrintWriter(error, true, StandardCharsets.UTF_8));
            ParseResult parsed = command.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parsed)) {
                if (command.getOut().checkError() || command.getErr().checkError())
                    throw new IOException("help output failed");
                return 0;
            }
            try (Reporter report = new Reporter(context, parsed)) {
                try {
                    report.record("debug", Machine.Event.COMMAND_STARTED, Documents.JSON.createObjectNode(), true);
                    if (parsed.hasMatchedOption("--generate")) {
                        if (parsed.hasSubcommand()) throw usage("--generate cannot accompany a command");
                        String target = parsed.matchedOptionValue("--generate", "schema");
                        String script =
                                switch (target) {
                                    case "bash" -> picocli.AutoComplete.bash("tmux-workspace", command);
                                    case "zsh" -> Completions.zsh("tmux-workspace", command.getCommandSpec());
                                    case "fish" -> Completions.fish("tmux-workspace", command.getCommandSpec());
                                    default -> null;
                                };
                        if (script != null) {
                            if (report.machine()) {
                                var artifact = Documents.JSON
                                        .createObjectNode()
                                        .put("schema_version", Machine.SCHEMA_VERSION)
                                        .put("command", "generate")
                                        .put("format", target)
                                        .put("script", script)
                                        .put("status", "ok");
                                if (report.streaming()) report.event(Machine.Event.COMPLETED, artifact);
                                else report.document(artifact);
                            } else output.write(script.getBytes(StandardCharsets.UTF_8));
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
                        case "debug-info" -> debugInfo(context, report);
                        default -> throw usage("select a workspace command; use --help");
                    }
                    output.flush();
                    return 0;
                } catch (Exception failure) {
                    try {
                        report.record(
                                "error",
                                Machine.Event.COMMAND_FAILED,
                                Documents.JSON
                                        .createObjectNode()
                                        .put("message", Objects.toString(failure.getMessage(), "command failed")),
                                false);
                    } catch (InterruptedIOException loggingInterrupted) {
                        Thread.currentThread().interrupt();
                    } catch (IOException | Failure loggingFailure) {
                        failure.addSuppressed(loggingFailure);
                        diagnostic(
                                context,
                                machine,
                                Machine.Code.USAGE,
                                Objects.toString(loggingFailure.getMessage(), "logging failed"));
                    }
                    throw failure;
                }
            }
        } catch (CommandLine.ParameterException failure) {
            diagnostic(
                    context, machine, Machine.Code.USAGE, Objects.toString(failure.getMessage(), "invalid arguments"));
            return 2;
        } catch (Failure
                | InterruptedException
                | io.github.libtmux.exception.LibTmuxException
                | IOException
                | UncheckedIOException
                | IllegalArgumentException failure) {
            Machine.Code code = classify(failure);
            if (code == Machine.Code.INTERRUPTED) Thread.currentThread().interrupt();
            diagnostic(
                    context,
                    machine,
                    code,
                    code == Machine.Code.INTERRUPTED
                            ? "operation interrupted"
                            : Objects.toString(failure.getMessage(), "command failed"));
            return status(failure, code);
        }
    }

    private static void debugInfo(Context context, Reporter report) throws IOException {
        com.fasterxml.jackson.databind.node.ArrayNode directories = Catalog.directories(context);
        String workingDirectory = Catalog.mask(context, context.directory());
        if (report.machine()) {
            report.document(Documents.JSON
                    .createObjectNode()
                    .put("port", "java")
                    .put("version", version())
                    .put("java_version", System.getProperty("java.version"))
                    .put("working_directory", workingDirectory)
                    .set("global_workspace_dirs", directories));
            return;
        }
        report.line("subject", "tmux-workspace", version());
        report.line("subject", "port", "java (" + System.getProperty("java.version") + ")");
        report.line("subject", "working_directory", workingDirectory);
        if (directories.isEmpty()) report.line("subject", "global_workspace_dirs", "(none)");
        else
            for (var directory : directories)
                report.line(
                        directory.path("active").asBoolean() ? "success" : "subject",
                        directory.path("path").asText(),
                        directory.path("source").asText() + ", "
                                + directory.path("workspace_count").asInt()
                                + " workspace(s)"
                                + (directory.path("exists").asBoolean() ? "" : ", missing"));
    }

    static boolean flag(ParseResult args, String name) {
        return args.matchedOptionValue(name, false);
    }

    static void diagnostic(Context context, boolean machine, Machine.Code code, String message) {
        try {
            String value = machine
                    ? Documents.JSON.writeValueAsString(Documents.JSON
                            .createObjectNode()
                            .put("schema_version", Machine.SCHEMA_VERSION)
                            .put("code", code.wire())
                            .put("message", message))
                    : "Error: " + Reporter.safe(message);
            context.error().write((value + "\n").getBytes(StandardCharsets.UTF_8));
            context.error().flush();
        } catch (IOException ignored) {
            // An unavailable error stream cannot carry another diagnostic.
        }
    }
}
