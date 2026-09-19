package consumer;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import io.github.libtmux.TextOutcome;
import io.github.libtmux.TypedText;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Touches every exported package, so hiding one would fail here rather than at a caller. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws InterruptedException {
        try (Server server = Server.builder()
                .endpoint(ServerEndpoint.socketPath(Path.of(args[0])))
                .build()) {
            Session session = server.newSession("consumer");
            Pane pane = session.activePane().orElseThrow();
            pane.sendLine("echo ready");
            TypedText typed = TypedText.in(pane);
            List<String> shown = typed.withoutEcho(pane.capture());
            TextOutcome outcome = pane.awaitText("ready", Duration.ofSeconds(5));
            System.out.println(outcome + " " + shown.size() + " " + server.identity().realm());
        }
    }
}
