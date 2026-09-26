package io.github.libtmux.consumer;

import io.github.libtmux.PaneRun;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drives a real tmux through the staged jar, loaded as a named module.
 *
 * <p>Exits non-zero on anything short of that: the module resolved without a name, a missing
 * transitive dependency, or a command that did not come back as it went in.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        Module library = Server.class.getModule();
        if (!library.isNamed()) {
            throw new AssertionError("libtmux loaded from the classpath, not as a module");
        }
        String expected = System.getProperty("libtmux.expectedVersion", "");
        String resolved = version(library);
        if (!resolved.equals(expected)) {
            throw new AssertionError("resolved libtmux " + resolved + ", not the staged " + expected);
        }
        Path root = Files.createDirectories(Path.of("/tmp/libtmux-java-test"));
        Path directory = Files.createTempDirectory(root, "consumer-");
        ServerConfig config = ServerConfig.builder()
                .endpoint(ServerEndpoint.socketPath(directory.resolve("s")))
                .build();
        try (Server server = Server.open(config)) {
            Session session = server.newSession("staged-consumer");
            PaneRun ran = session.windows().get(0).panes().get(0).run("printf 'staged\\n'", Duration.ofSeconds(30));
            server.killServer();
            if (!ran.output().equals(List.of("staged")) || ran.exitStatus().orElse(-1) != 0) {
                throw new AssertionError("the command did not come back as it went in: " + ran);
            }
        } finally {
            try (Stream<Path> left = Files.walk(directory)) {
                left.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        System.out.println("staged consumer ran through " + library.getName() + "@" + resolved);
    }

    private static String version(Module library) {
        return library.getDescriptor().rawVersion().orElse("(no module version)");
    }
}
