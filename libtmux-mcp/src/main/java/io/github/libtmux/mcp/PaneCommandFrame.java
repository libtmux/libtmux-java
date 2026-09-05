package io.github.libtmux.mcp;

import io.github.libtmux.ServerConfig;
import io.github.libtmux.transport.CommandResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** The exact tmux client and live socket used by one pane-command frame. */
record PaneCommandFrame(List<String> client) {

    PaneCommandFrame {
        client = List.copyOf(client);
    }

    static PaneCommandFrame resolve(Call call) {
        return resolve(
                call.server().config(),
                call.surface().resolvedSocketPath(),
                System.getenv(),
                Path.of("").toAbsolutePath(),
                () -> call.server().cmd("display-message", "-p", "#{socket_path}"));
    }

    static PaneCommandFrame resolve(
            ServerConfig config,
            Optional<String> suppliedSocket,
            Map<String, String> environment,
            Path workingDirectory,
            Supplier<CommandResult> socketQuery) {
        String executable = resolveExecutable(config.binary(), environment, workingDirectory);
        String socket = resolveSocket(suppliedSocket, socketQuery);
        return new PaneCommandFrame(List.of(executable, "-S", socket));
    }

    static String resolveExecutable(String configured, Map<String, String> environment, Path workingDirectory) {
        if (configured.indexOf(File.separatorChar) >= 0) {
            Path selected = Path.of(configured);
            return requireExecutable(selected.isAbsolute() ? selected : workingDirectory.resolve(selected), configured);
        }

        String path = environment.get("PATH");
        if (path == null) {
            throw new IllegalArgumentException("PATH is required to resolve tmux executable '" + configured + "'");
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            Path directory = entry.isEmpty() ? workingDirectory : Path.of(entry);
            if (!directory.isAbsolute()) {
                directory = workingDirectory.resolve(directory);
            }
            Path candidate = directory.resolve(configured);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return requireExecutable(candidate, configured);
            }
        }
        throw new IllegalArgumentException("tmux executable '" + configured + "' was not found on PATH");
    }

    private static String requireExecutable(Path selected, String configured) {
        if (!Files.isRegularFile(selected) || !Files.isExecutable(selected)) {
            throw new IllegalArgumentException("tmux executable '" + configured + "' is not a regular executable file");
        }
        try {
            Path resolved = selected.toRealPath();
            if (!resolved.isAbsolute() || !Files.isRegularFile(resolved) || !Files.isExecutable(resolved)) {
                throw new IllegalArgumentException(
                        "tmux executable '" + configured + "' did not resolve to an absolute executable file");
            }
            return resolved.toString();
        } catch (IOException failure) {
            throw new IllegalArgumentException("tmux executable '" + configured + "' could not be resolved", failure);
        }
    }

    static String resolveSocket(Optional<String> supplied, Supplier<CommandResult> socketQuery) {
        if (supplied.isPresent() && !supplied.orElseThrow().isBlank()) {
            return requireAbsoluteSocket(supplied.orElseThrow());
        }
        CommandResult result = socketQuery.get();
        if (!result.succeeded() || result.stdout().size() != 1) {
            throw new IllegalArgumentException("tmux did not report exactly one socket path");
        }
        return requireAbsoluteSocket(result.stdout().getFirst());
    }

    private static String requireAbsoluteSocket(String socket) {
        if (socket.isBlank() || !Path.of(socket).isAbsolute()) {
            throw new IllegalArgumentException("tmux socket path must be nonblank and absolute");
        }
        return socket;
    }
}
