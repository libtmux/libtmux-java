package io.github.libtmux.it;

import io.github.libtmux.Server;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A second control client, attached and then stopped with SIGSTOP, or nothing at all.
 *
 * <p>tmux leaves the daemon running after {@code kill-server} until every client has left, so a wait
 * that decided the server was gone by counting clients or by a timer would answer differently with
 * one stopped on the socket. Each case runs both ways.
 */
final class StoppedClient implements AutoCloseable {

    private final @Nullable Process process;

    private StoppedClient(@Nullable Process process) {
        this.process = process;
    }

    /** Attaches and stops a client to {@code session} when {@code wanted}; otherwise does nothing. */
    static StoppedClient attach(Server server, String session, boolean wanted)
            throws IOException, InterruptedException {
        if (!wanted) {
            return new StoppedClient(null);
        }
        List<String> argv = new ArrayList<>(server.config().endpointCommand());
        argv.addAll(List.of("-C", "attach-session", "-t", session));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().remove("TMUX");
        Process client = builder.redirectErrorStream(true).start();
        StoppedClient stopped = new StoppedClient(client);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.clients().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        if (server.clients().isEmpty()) {
            stopped.close();
            throw new IOException("the control client never attached");
        }
        signal("STOP", client.pid());
        return stopped;
    }

    private static void signal(String name, long pid) throws IOException, InterruptedException {
        new ProcessBuilder("kill", "-" + name, Long.toString(pid)).start().waitFor();
    }

    @Override
    public void close() throws IOException {
        Process client = process;
        if (client == null) {
            return;
        }
        try {
            signal("CONT", client.pid());
            client.getOutputStream().close();
            if (!client.waitFor(5, TimeUnit.SECONDS)) client.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            client.destroyForcibly();
        }
    }
}
