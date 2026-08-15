package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What the control carrier owes a caller that closes it while it is still working something out. */
final class ControlTransportTest {

    private static final ServerConfig CONFIG = ServerConfig.builder()
            // Nothing here may reach a real tmux. A control client that did start would have to be
            // waited for, and this test is about the one that must never be started.
            .binary("libtmux-no-such-tmux-binary")
            .endpoint(ServerEndpoint.socketPath(Path.of("/tmp/libtmux-java-dev/unit/s")))
            .build();

    private static CommandRequest request() {
        return new CommandRequest(CONFIG.endpointCommand(), List.of("list-windows"), Duration.ofSeconds(5));
    }

    /**
     * Attaching takes two steps — find a session, then attach to it — and a transport can be closed
     * between them. That close cannot see a client which does not exist yet, so the attach is what
     * has to notice; otherwise it leaves a tmux client and its reader thread behind with nothing
     * holding either.
     *
     * <p>The close is driven from inside the session lookup, which is exactly where it would have to
     * land for the race to happen at all.
     */
    @Test
    void aCloseLandingMidAttachLeavesNoClientBehind() {
        AtomicReference<ControlTransport> holder = new AtomicReference<>();
        TmuxTransport bootstrap = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest ignored) {
                ControlTransport transport = holder.get();
                if (transport != null) {
                    transport.close();
                }
                return new CommandResult(0, List.of("$0"), List.of());
            }

            @Override
            public void close() {}
        };
        ControlTransport transport = new ControlTransport(CONFIG, bootstrap);
        holder.set(transport);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> transport.execute(request()));
        String reason = String.valueOf(refused.getMessage());

        assertTrue(
                reason.contains("closed"),
                "a closed transport says so, rather than failing at starting a client it should never "
                        + "have started: " + reason);
    }
}
