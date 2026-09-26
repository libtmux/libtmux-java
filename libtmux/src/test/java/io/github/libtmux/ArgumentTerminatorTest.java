package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Caller text never reaches tmux where tmux would read it as a flag.
 *
 * <p>Swept rather than spot-checked, because this defect is invisible: {@code set-buffer} took
 * {@code -nfoo} as "name it foo", wrote nothing, and reported success. Every public method that
 * hands tmux a value the caller chose is driven here with a value beginning with a dash, and the
 * options must already be ended when it arrives.
 */
final class ArgumentTerminatorTest {

    /** Begins with a dash, and is not a flag any tmux command takes. */
    private static final String DASHED = "-libtmux-marker";

    private static final String SEP = RowFormat.of("x").separator();

    @Test
    void everyCallerValueReachesTmuxWithTheOptionsAlreadyEnded() {
        Map<String, Consumer<Server>> sites = new LinkedHashMap<>();
        sites.put("Server.expand", server -> server.expand(DASHED));
        sites.put("Server.runShell", server -> server.runShell(DASHED));
        sites.put("Server.runShellCapturing", server -> server.runShellCapturing(DASHED));
        sites.put("Window.displayPopup", server -> server.windows().get(0).displayPopup(DASHED));
        sites.put("Pane.pipeTo", server -> server.panes().get(0).pipeTo(DASHED));
        sites.put("Keys.bind", server -> server.keys().bind(DASHED, List.of("display-message", "hi")));
        sites.put("Keys.unbind", server -> server.keys().unbind(DASHED));
        sites.put("Server.sourceFile", server -> server.sourceFile(Path.of(DASHED)));
        sites.put("Channel.signal", server -> server.channel(DASHED).signal());
        sites.put("Options.set", server -> server.globalOptions().set("@x", DASHED));
        sites.put("Options.setExpanded", server -> server.globalOptions().setExpanded("@x", DASHED));
        sites.put("Options.append", server -> server.globalOptions().append("@x", DASHED));
        sites.put("Options.unset", server -> server.globalOptions().unset(DASHED));
        sites.put("Options.get", server -> server.globalOptions().get(DASHED));
        sites.put("Hooks.set", server -> server.hooks().set(DASHED, "display-message hi"));
        sites.put("Hooks.unset", server -> server.hooks().unset(DASHED));
        sites.put("Hooks.run", server -> server.hooks().run(DASHED));
        sites.put("Environment.set", server -> server.environment().set("K", DASHED));
        sites.put("Environment.setExpanded", server -> server.environment().setExpanded("K", DASHED));
        sites.put("Environment.get", server -> server.environment().get(DASHED));
        sites.put("Buffers.set", server -> server.buffers().set("b", DASHED));
        sites.put("Buffers.save", server -> server.buffers().save("b", Path.of(DASHED)));
        sites.put("Buffers.load", server -> server.buffers().load("b", Path.of(DASHED)));
        sites.put("Session.rename", server -> {
            var unused = server.sessions().get(0).rename(DASHED);
        });
        sites.put("Window.rename", server -> {
            var unused = server.windows().get(0).rename(DASHED);
        });
        sites.put("Pane.retitle", server -> {
            var unused = server.panes().get(0).retitle(DASHED);
        });
        sites.put("Pane.send", server -> server.panes().get(0).send(DASHED));
        sites.put("Pane.sendKeys", server -> server.panes().get(0).sendKeys(List.of(DASHED)));
        sites.put("Pane.sendLiteral", server -> server.panes().get(0).sendLiteral(List.of(DASHED)));
        sites.put("Pane.sendLine", server -> server.panes().get(0).sendLine(DASHED));

        List<String> unguarded = new ArrayList<>();
        sites.forEach((name, call) -> {
            RecordingTmux tmux = new RecordingTmux();
            try (Server server = tmux.server()) {
                call.accept(server);
            }
            List<String> carrying = tmux.carrying(DASHED);
            if (carrying.isEmpty()) {
                fail(name + " never handed tmux the value it was given");
            }
            if (!guarded(carrying)) {
                unguarded.add(name + " sent " + carrying);
            }
        });

        assertTrue(unguarded.isEmpty(), "tmux would read these caller values as flags: " + unguarded);
    }

    @Test
    void sessionNamesAreResolvedLocallyAndNeverBecomeArguments() {
        RecordingTmux tmux = new RecordingTmux();
        try (Server server = tmux.server()) {
            assertTrue(server.hasSession(DASHED));
            server.killSession(DASHED);
        }
        assertTrue(tmux.carrying(DASHED).isEmpty());
        assertTrue(tmux.seen.contains(List.of("kill-session", "-t", "$0")));
    }

    @Test
    void creationCommandsEndOptionsBeforeTheProgram() {
        TmuxVersion version = new TmuxVersion(3, 7, "c");
        List<List<String>> commands = List.of(
                SessionSpec.builder().running(DASHED).build().argv("#{pane_id}", () -> version),
                WindowSpec.builder().running(DASHED).build().argv("$0", "#{pane_id}", version),
                SplitSpec.builder().running(DASHED).build().argv("%0", "#{pane_id}", version));

        for (List<String> argv : commands) {
            assertTrue(guarded(argv), "tmux would read the program as flags: " + argv);
        }
    }

    /**
     * Whether tmux will read the caller's value as a value.
     *
     * <p>Two ways to be safe, and both count: the options are already ended, or the value is the
     * argument of the flag before it, which tmux consumes whatever it looks like.
     */
    private static boolean guarded(List<String> argv) {
        int value = -1;
        for (int index = 0; index < argv.size(); index++) {
            if (argv.get(index).contains(DASHED)) {
                value = index;
                break;
            }
        }
        if (value <= 0) {
            return false;
        }
        String before = argv.get(value - 1);
        if (before.equals("--")) {
            return true;
        }
        if (before.startsWith("-") && before.length() > 1) {
            return true;
        }
        return argv.subList(0, value).contains("--");
    }

    // ------------------------------------------------------------------------------- fixtures

    /** A tmux that answers a minimal server and remembers every command it was asked to run. */
    private static final class RecordingTmux implements TmuxTransport {

        private final List<List<String>> seen = new ArrayList<>();

        Server server() {
            return Server.using(
                    ServerConfig.builder()
                            .endpoint(ServerEndpoint.namedSocket("fixture"))
                            .build(),
                    this);
        }

        /** The command that carried this value, or empty when none did. */
        List<String> carrying(String value) {
            return seen.stream()
                    .filter(argv -> argv.stream().anyMatch(word -> word.contains(value)))
                    .findFirst()
                    .orElse(List.of());
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            return GroupedTmux.execute(request, 4242L, argv -> {
                seen.add(argv);
                return answer(argv);
            });
        }

        private CommandResult answer(List<String> argv) {
            return switch (argv.get(0)) {
                case "display-message" ->
                    new CommandResult(0, List.of(argv.contains("#{version}") ? "3.6" : row("4242", "3.6")), List.of());
                case "list-sessions" -> new CommandResult(0, List.of(row("$0", DASHED, "1", "1")), List.of());
                case "list-windows" ->
                    new CommandResult(
                            0, List.of(row("$0", "@7", "0", "editor", "1", "1", "1", "80", "24", "layout")), List.of());
                case "list-panes" ->
                    new CommandResult(
                            0,
                            List.of(row(
                                    "$0", "@7", "0", "%1", "0", "1", "sh", "80", "24", "0", "0", "t", "/tmp", "11", "1",
                                    "1", "1", "1")),
                            List.of());
                default -> new CommandResult(0, List.of(), List.of());
            };
        }

        private static String row(String... fields) {
            return String.join(SEP, fields);
        }

        @Override
        public void close() {}
    }
}
