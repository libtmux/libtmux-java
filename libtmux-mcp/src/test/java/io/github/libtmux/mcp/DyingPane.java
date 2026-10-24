package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** A pane that prints once and then idles, on a server that has another session to stay up in. */
final class DyingPane implements AutoCloseable {

    static final String PRINTED = "printed-before-the-end";

    final Pane pane;
    private final StoppedClient client;

    private DyingPane(Pane pane, StoppedClient client) {
        this.pane = pane;
        this.client = client;
    }

    /** A pane that prints {@link #PRINTED} a moment after it starts, beside a session that outlives it. */
    static DyingPane start(Server server, boolean withStoppedClient) throws IOException, InterruptedException {
        server.newSession(session -> session.named("outlives"));
        Pane pane = server.newSession(session ->
                        session.named("victim").running("sh", "-c", "sleep 1; echo " + PRINTED + "; sleep 300"))
                .windows()
                .get(0)
                .panes()
                .get(0);
        return new DyingPane(pane, StoppedClient.attach(server, "outlives", withStoppedClient));
    }

    /** Waits for the pane to have printed, then for the wait to have had time to read it. */
    void awaitPrinted() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (pane.capture().stream().anyMatch(line -> line.contains(PRINTED))) {
                Thread.sleep(600);
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("the pane never printed");
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
