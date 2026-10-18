package io.github.libtmux.workspace.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The README's workspace example, run as written.
 *
 * <p>{@link DocumentedCommandsTest} holds every documented command to the parser. This goes
 * further for the one example the README builds on: it saves the workspace file the README shows,
 * runs the {@code load} and {@code freeze} commands the README shows against a real tmux, and reads
 * what they print. Only the socket differs, because the README's path is a developer's scratch
 * directory and a test's belongs under the test root.
 */
final class ReadmeExamplesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private record Result(int code, String out, String err) {}

    private Result invoke(List<String> arguments) {
        var environment = new HashMap<>(System.getenv());
        environment.remove("TMUX");
        environment.remove("TMUX_PANE");
        environment.put("HOME", directory.toString());
        environment.put("LIBTMUX_TEST_TMUX", System.getProperty("libtmux.tmux", "tmux"));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.run(
                arguments.toArray(String[]::new), environment, directory, InputStream.nullInputStream(), out, err);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** The documented command whose subcommand is {@code subcommand}, with the socket swapped in. */
    private static List<String> documented(String readme, String subcommand, Path socket) {
        for (List<String> words : MarkdownCommands.consoleCommands(readme)) {
            List<String> arguments = MarkdownCommands.argumentsOf(words, "tmux-workspace");
            if (!arguments.isEmpty() && arguments.get(0).equals(subcommand)) {
                List<String> swapped = new ArrayList<>(arguments);
                int flag = swapped.indexOf("-S");
                assertTrue(flag >= 0 && flag + 1 < swapped.size(), "the documented " + subcommand + " names no -S");
                swapped.set(flag + 1, socket.toString());
                return swapped;
            }
        }
        throw new AssertionError("the README shows no `tmux-workspace " + subcommand + "` command");
    }

    @Test
    void theLoadAndFreezeExamplesRunAsWritten() throws Exception {
        String readme = Files.readString(MarkdownCommands.repository().resolve("libtmux-workspace-cli/README.md"));
        Matcher file = Pattern.compile("(?s)```yaml\\n(.*?)```").matcher(readme);
        assertTrue(file.find(), "the README shows no workspace file");
        Files.writeString(directory.resolve("workspace.yaml"), file.group(1));
        Path socket = directory.resolve("workspace-example.sock");

        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(socket))
                .binary(System.getProperty("libtmux.tmux", "tmux"))
                .configFile(Path.of("/dev/null"))
                .build()) {
            try {
                Result loaded = invoke(documented(readme, "load", socket));

                assertEquals(0, loaded.code(), loaded.toString());
                List<String> events = loaded.out().lines().toList();
                JsonNode last = JSON.readTree(events.getLast());
                assertEquals("completed", last.path("event").asText(), loaded.out());
                assertEquals("ok", last.path("status").asText(), loaded.out());
                assertTrue(server.hasSession("dev"), "loading did not leave the session the README names");

                Result frozen = invoke(documented(readme, "freeze", socket));

                assertEquals(0, frozen.code(), frozen.toString());
                JsonNode captured = JSON.readTree(frozen.out());
                assertEquals("dev", captured.path("session_name").asText(), frozen.out());
                assertEquals(2, captured.path("windows").path(0).path("panes").size(), frozen.out());
            } finally {
                if (server.isAlive()) {
                    server.killServer();
                }
            }
        }
    }
}
