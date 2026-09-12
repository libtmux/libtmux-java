package io.github.libtmux.workspace.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Children {
    private static final int LIMIT = 1024 * 1024;

    private Children() {}

    record Output(int status, String stdout, String stderr, boolean truncated) {
        ObjectNode value() {
            return Documents.JSON
                    .createObjectNode()
                    .put("child_status", status)
                    .put("stdout", stdout)
                    .put("stderr", stderr)
                    .put("truncated", truncated)
                    .put("encoding", "utf-8-with-replacement");
        }
    }

    private record Capture(String text, boolean truncated) {}

    static String executable(Main.Context context, String name) {
        if (name.contains("/"))
            return context.directory().resolve(name).normalize().toString();
        for (String directory : context.environment().getOrDefault("PATH", "").split(":", -1)) {
            if (directory.isEmpty()) continue;
            Path candidate = context.directory().resolve(directory).resolve(name);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) return candidate.toString();
        }
        throw new Main.Failure("executable_not_found", 1, "executable is not on PATH: " + name);
    }

    static Output run(Main.Context context, List<String> argv, Path directory, Reporter report, Duration timeout)
            throws IOException, InterruptedException {
        return run(context, argv, directory, report, timeout, true);
    }

    private static Output run(
            Main.Context context, List<String> argv, Path directory, Reporter report, Duration timeout, boolean events)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(argv);
        command.set(0, executable(context, command.getFirst()));
        boolean grouped = Files.isDirectory(Path.of("/proc/self")) && Files.isExecutable(Path.of("/usr/bin/setsid"));
        if (grouped) command.addFirst("/usr/bin/setsid");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(context.environment());
        Process child = builder.start();
        child.getOutputStream().close();
        var drains = Executors.newFixedThreadPool(2);
        List<ProcessHandle> descendants = new ArrayList<>();
        boolean success = false;
        try {
            Future<Capture> stdout = drains.submit(() -> capture(child.getInputStream(), "stdout", report, events));
            Future<Capture> stderr = drains.submit(() -> capture(child.getErrorStream(), "stderr", report, events));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!child.waitFor(25, TimeUnit.MILLISECONDS)) {
                child.descendants()
                        .filter(handle -> !descendants.contains(handle))
                        .forEach(descendants::add);
                if (stdout.isDone()) result(stdout);
                if (stderr.isDone()) result(stderr);
                if (System.nanoTime() >= deadline)
                    throw new Main.Failure("child_timeout", 1, "child execution timed out");
            }
            Capture out = result(stdout);
            Capture err = result(stderr);
            if (grouped && hasCapturedDescendant(child.pid())) {
                throw new Main.Failure("child_stream_timeout", 1, "child left a process using captured output streams");
            }
            success = child.exitValue() == 0;
            return new Output(child.exitValue(), out.text(), err.text(), out.truncated() || err.truncated());
        } finally {
            if (!success) {
                if (grouped) terminateGroup(child.pid());
                descendants.forEach(ProcessHandle::destroyForcibly);
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
            drains.shutdownNow();
        }
    }

    private static boolean hasCapturedDescendant(long group) throws IOException, InterruptedException {
        Process list = new ProcessBuilder("/bin/ps", "-o", "pid=", "--sid", Long.toString(group))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!list.waitFor(1, TimeUnit.SECONDS)) throw new IOException("owned process lookup timed out");
            String pids = new String(list.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String pid : pids.lines().map(String::strip).toList()) {
                if (!pid.matches("[0-9]+")) continue;
                for (String descriptor : List.of("1", "2")) {
                    try {
                        String target = Files.readSymbolicLink(Path.of("/proc", pid, "fd", descriptor))
                                .toString();
                        if (target.startsWith("pipe:[")) return true;
                    } catch (java.nio.file.NoSuchFileException disappeared) {
                        break;
                    }
                }
            }
            return false;
        } finally {
            if (list.isAlive()) list.destroyForcibly();
        }
    }

    private static void terminateGroup(long group) {
        boolean interrupted = Thread.interrupted();
        try {
            Process kill = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + group)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!kill.waitFor(1, TimeUnit.SECONDS)) kill.destroyForcibly();
        } catch (IOException unavailable) {
            // The caller also terminates known process handles.
        } catch (InterruptedException cancelled) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static Capture result(Future<Capture> future) throws IOException, InterruptedException {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("child stream failed", cause);
        } catch (TimeoutException timeout) {
            throw new Main.Failure("child_stream_timeout", 1, "child exited with inherited output streams still open");
        }
    }

    private static Capture capture(InputStream input, String channel, Reporter report, boolean events)
            throws IOException {
        StringBuilder retained = new StringBuilder();
        boolean truncated = false;
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            for (int count; (count = reader.read(buffer)) >= 0; ) {
                String chunk = new String(buffer, 0, count);
                int keep = Math.min(count, LIMIT - retained.length());
                retained.append(buffer, 0, keep);
                truncated |= keep < count;
                if (events)
                    report.event(
                            "script-output",
                            Documents.JSON
                                    .createObjectNode()
                                    .put("stream", channel)
                                    .put("text", chunk));
            }
        }
        return new Capture(retained.toString(), truncated);
    }

    static List<String> words(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int at = 0; at < command.length(); at++) {
            char value = command.charAt(at);
            if (value == 0) throw Main.usage("child command cannot contain NUL");
            if (value == '\\' && quote != '\'') {
                if (++at == command.length()) throw Main.usage("unfinished escape in child command");
                char next = command.charAt(at);
                if (quote == '"' && next != '"' && next != '\\') word.append('\\');
                word.append(next);
                started = true;
            } else if (quote != 0) {
                if (value == quote) quote = 0;
                else word.append(value);
            } else if (value == '\'' || value == '"') {
                quote = value;
                started = true;
            } else if (Character.isWhitespace(value)) {
                if (started) {
                    result.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(value);
                started = true;
            }
        }
        if (quote != 0) throw Main.usage("unclosed quote in child command");
        if (started) result.add(word.toString());
        if (result.isEmpty()) throw Main.usage("child command requires an executable");
        return List.copyOf(result);
    }

    static void edit(Main.Context context, picocli.CommandLine.ParseResult args, Reporter report)
            throws IOException, InterruptedException {
        Path source = Catalog.resolve(context, args.matchedPositionalValue(0, ""), "edit");
        var command = new ArrayList<>(words(context.environment().getOrDefault("EDITOR", "vi")));
        command.add(source.toString());
        Output output = Main.controllingTerminal()
                ? terminal(context, command)
                : run(context, command, context.directory(), report, Duration.ofHours(24));
        ObjectNode result = output.value()
                .put("schema_version", 1)
                .put("command", "edit")
                .put("path", Catalog.mask(context, source))
                .put("status", output.status() == 0 ? "ok" : "error");
        complete(report, result, output.status());
    }

    static String python(Main.Context context, Reporter report) throws InterruptedException {
        try {
            String executable =
                    executable(context, context.environment().getOrDefault("TMUX_WORKSPACE_PYTHON", "python3"));
            Output version = run(
                    context,
                    List.of(executable, "-c", "import importlib.metadata; print(importlib.metadata.version('tmuxp'))"),
                    context.directory(),
                    report,
                    Duration.ofSeconds(5),
                    false);
            if (version.status() != 0 || !version.stdout().strip().equals("1.74.0"))
                throw new Main.Failure("python_runtime", 1, "tmuxp 1.74.0 is required");
            return executable;
        } catch (IOException | Main.Failure absent) {
            throw new Main.Failure(
                    "python_runtime", 1, "set TMUX_WORKSPACE_PYTHON to an interpreter with tmuxp 1.74.0 installed");
        }
    }

    static void shell(Main.Context context, picocli.CommandLine.ParseResult args, Reporter report)
            throws IOException, InterruptedException {
        String code = args.matchedOptionValue("-c", "");
        boolean interactive = !args.hasMatchedOption("-c");
        if (interactive && !Main.controllingTerminal())
            throw Main.usage("interactive Python shell requires a terminal; use -c for captured output");
        var command = new ArrayList<>(
                List.of(python(context, report), "-u", "-c", "from tmuxp.cli import cli; cli()", "shell"));
        if (!interactive) {
            command.add("-c");
            command.add(code);
        }
        for (String option : List.of("-S", "-L")) {
            if (args.hasMatchedOption(option)) {
                command.add(option);
                command.add(args.matchedOptionValue(option, ""));
            }
        }
        for (String option :
                List.of("--best", "--pdb", "--code", "--ptipython", "--ptpython", "--ipython", "--bpython")) {
            if (Main.flag(args, option)) command.add(option);
        }
        for (String[] pair : List.of(
                new String[] {"--use-pythonrc", "--no-startup"}, new String[] {"--use-vi-mode", "--no-vi-mode"})) {
            String last = "";
            for (String argument : args.originalArgs()) if (List.of(pair).contains(argument)) last = argument;
            if (!last.isEmpty()) command.add(last);
        }
        for (int index = 0; index < 2; index++) {
            String value = args.matchedPositionalValue(index, "");
            if (!value.isEmpty()) command.add(value);
        }
        Output output = interactive
                ? terminal(context, command)
                : run(context, command, context.directory(), report, Duration.ofHours(24));
        complete(
                report,
                output.value()
                        .put("schema_version", 1)
                        .put("command", "shell")
                        .put("status", output.status() == 0 ? "ok" : "error"),
                output.status());
    }

    static java.io.File terminalDevice(Main.Context context) throws IOException, InterruptedException {
        Process child = new ProcessBuilder(
                        executable(context, "ps"),
                        "-p",
                        Long.toString(ProcessHandle.current().pid()),
                        "-o",
                        "tty=")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!child.waitFor(1, java.util.concurrent.TimeUnit.SECONDS))
                throw Main.usage("cannot resolve the controlling terminal device");
            String name = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (child.exitValue() != 0 || name.isEmpty() || name.equals("?"))
                throw Main.usage("a controlling terminal is required");
            return Path.of(name.startsWith("/") ? name : "/dev/" + name).toFile();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static Output terminal(Main.Context context, List<String> command)
            throws IOException, InterruptedException {
        var argv = new ArrayList<>(command);
        argv.set(0, executable(context, argv.getFirst()));
        var builder = new ProcessBuilder(argv).directory(context.directory().toFile());
        builder.environment().clear();
        builder.environment().putAll(context.environment());
        java.io.File tty = terminalDevice(context);
        Process child = builder.redirectInput(tty)
                .redirectOutput(tty)
                .redirectError(tty)
                .start();
        try {
            return new Output(child.waitFor(), "", "", false);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static void complete(Reporter report, ObjectNode result, int status) throws IOException {
        if (report.streaming()) report.event(status == 0 ? "completed" : "failed", result);
        else if (report.machine()) report.document(result);
        else if (!result.path("stdout").asText().isEmpty())
            report.line("information", "Output", result.path("stdout").asText());
        if (status != 0) throw new Main.Failure("child_failed", status, "child exited with " + status);
    }
}
