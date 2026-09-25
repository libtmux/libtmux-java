package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Nothing answering the socket is one answer, whichever method asked.
 *
 * <p>{@code MIGRATION.md} tells a caller to catch {@link ServerUnavailableException} rather than match
 * on a message, which only works if every method raises it. Seven reads and every mutation used to
 * raise a plain {@link LibTmuxException} carrying tmux's own wording, because the check lived at the
 * call sites that remembered it rather than at the one place a failure is built.
 *
 * <p>Swept rather than spot-checked for the reason the check went missing in the first place: a
 * method added later inherits the rule only if something looks at all of them.
 */
final class AbsentDaemonTest {

    @Test
    void everyReadAndEveryMutationSaysTheDaemonIsNotThere() {
        Map<String, Consumer<Server>> sites = new LinkedHashMap<>();
        sites.put("sessions", Server::sessions);
        sites.put("windows", Server::windows);
        sites.put("panes", Server::panes);
        sites.put("snapshot", Server::snapshot);
        sites.put("session(name)", server -> server.session("x"));
        sites.put("hasSession", server -> server.hasSession("x"));
        sites.put("clients", Server::clients);
        sites.put("version", Server::version);
        sites.put("requireAlive", Server::requireAlive);
        sites.put("keys.list", server -> server.keys().list());
        sites.put("commands.list", server -> server.commands().list());
        sites.put("messageLog.lines", server -> server.messageLog().lines());
        sites.put("expand", server -> server.expand("#{pid}"));
        sites.put("options.get", server -> server.globalOptions().get("status"));
        sites.put("options.all", server -> server.globalOptions().all());
        sites.put("environment.get", server -> server.environment().get("PATH"));
        sites.put("environment.isRemoved", server -> server.environment().isRemoved("PATH"));
        sites.put("environment.all", server -> server.environment().all());
        sites.put("environment.removed", server -> server.environment().removed());
        sites.put("hooks.all", server -> server.hooks().all());
        sites.put("buffers.list", server -> server.buffers().list());
        sites.put("buffers.show", server -> server.buffers().show("b"));
        sites.put("killSession", server -> server.killSession("x"));
        sites.put("options.set", server -> server.globalOptions().set("@x", "1"));
        sites.put("environment.set", server -> server.environment().set("K", "v"));
        sites.put("buffers.set", server -> server.buffers().set("b", "v"));
        sites.put("hooks.set", server -> server.hooks().set("after-new-window", "display-message hi"));
        sites.put("keys.bind", server -> server.keys().bind("F12", List.of("display-message", "hi")));
        sites.put("sourceFile", server -> server.sourceFile(Path.of("/tmp/nothing.conf")));
        sites.put("shell.run", server -> server.shell().run("true"));
        sites.put("newSession", server -> {
            var unused = server.newSession("s");
        });

        List<String> wrong = new ArrayList<>();
        sites.forEach((name, call) -> {
            try (Server server = absent()) {
                call.accept(server);
                wrong.add(name + " did not raise at all");
            } catch (ServerUnavailableException expected) {
                // What every one of them has to say.
            } catch (RuntimeException other) {
                wrong.add(name + " raised " + other.getClass().getSimpleName() + ": " + other.getMessage());
            }
        });

        assertTrue(wrong.isEmpty(), "a missing daemon was reported as something else: " + wrong);
    }

    /** A socket nothing serves, which tmux reports the same way for every command. */
    private static Server absent() {
        return Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("absent"))
                        .defaultTimeout(Duration.ofSeconds(1))
                        .build(),
                new TmuxTransport() {
                    @Override
                    public CommandResult execute(CommandRequest request) {
                        return new CommandResult(
                                1, List.of(), List.of("error connecting to /tmp/absent (No such file or directory)"));
                    }

                    @Override
                    public void close() {}
                });
    }
}
