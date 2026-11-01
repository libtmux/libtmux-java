package io.github.libtmux.testsandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * The JUnit Platform stops a run that could reach a server it was not given.
 *
 * <p>The refusal is exercised in a child JVM that is handed an environment, because this JVM's own
 * cannot be changed. The child prints which way it ended and never a value.
 */
class SandboxSessionListenerTest {

    private static final String SANDBOX = "/tmp/libtmux-java-test/0123456789abcdef";

    @Test
    void theLauncherLoadsTheListenerOnItsOwn() {
        boolean registered = ServiceLoader.load(LauncherSessionListener.class).stream()
                .anyMatch(provider -> provider.type() == SandboxSessionListener.class);

        assertTrue(registered, "the JUnit Platform would not load the sandbox listener");
    }

    @Test
    void aSessionOpenedInAPaneIsRefused() throws Exception {
        Outcome outcome = openSessionIn(Map.of("TMUX", "decoy", "TMUX_TMPDIR", SANDBOX), SANDBOX);

        assertEquals("refused", outcome.result(), "a session opened with TMUX set was not refused");
        assertTrue(outcome.reported().contains("TMUX"), "the refusal did not name the variable");
    }

    @Test
    void aSessionOpenedWithNoQuarantineIsRefused() throws Exception {
        Outcome outcome = openSessionIn(Map.of(), null);

        assertEquals("refused", outcome.result(), "a session no build task prepared was not refused");
    }

    @Test
    void aSessionTheBuildPreparedOpens() throws Exception {
        Outcome outcome = openSessionIn(Map.of("TMUX_TMPDIR", SANDBOX), SANDBOX);

        assertEquals("opened", outcome.result(), "a session the build prepared was refused");
        assertFalse(outcome.reported().contains("TMUX"), "the control reported a variable it was not given");
    }

    private record Outcome(String result, String reported) {}

    private static Outcome openSessionIn(Map<String, String> environment, @Nullable String sandbox) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        if (sandbox != null) {
            command.add("-D" + SandboxCheck.SANDBOX_PROPERTY + "=" + sandbox);
        }
        command.add(Child.class.getName());
        ProcessBuilder child = new ProcessBuilder(command).redirectErrorStream(true);
        child.environment().clear();
        child.environment().putAll(environment);
        Process started = child.start();
        String output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(started.waitFor(60, TimeUnit.SECONDS), "the child JVM did not exit");
        String result = output.lines()
                .filter(line -> line.startsWith("RESULT "))
                .map(line -> line.substring("RESULT ".length()))
                .findFirst()
                .orElse("no result");
        return new Outcome(result, output);
    }

    /** Opens a launcher session the way the JUnit Platform does, and says how that ended. */
    public static final class Child {
        private Child() {}

        /**
         * Runs in a child JVM.
         *
         * @param arguments none
         */
        public static void main(String[] arguments) {
            LauncherSession session = (LauncherSession) Proxy.newProxyInstance(
                    Child.class.getClassLoader(),
                    new Class<?>[] {LauncherSession.class},
                    (proxy, method, args) -> null);
            try {
                new SandboxSessionListener().launcherSessionOpened(session);
                System.out.println("RESULT opened");
            } catch (IllegalStateException refusal) {
                System.out.println("RESULT refused");
                System.out.println(refusal.getMessage());
            }
        }
    }
}
