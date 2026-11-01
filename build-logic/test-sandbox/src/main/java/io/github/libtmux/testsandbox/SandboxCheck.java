package io.github.libtmux.testsandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What would let a test process reach a tmux server it was not given.
 *
 * <p>A finding names a variable and never prints its value, because an environment holds secrets and
 * a failure message is copied into logs.
 */
final class SandboxCheck {

    /** The system property the build sets to the directory it exported as {@code TMUX_TMPDIR}. */
    static final String SANDBOX_PROPERTY = "libtmux.test.sandbox";

    private SandboxCheck() {}

    /**
     * Every way {@code environment} still points a bare tmux client somewhere unowned.
     *
     * @param environment the process environment
     * @param sandbox the directory the build prepared for this task, or {@code null} when it did not
     * @return one sentence per finding, empty when the process is sandboxed
     */
    static List<String> findings(Map<String, String> environment, @Nullable String sandbox) {
        List<String> found = new ArrayList<>();
        if (environment.containsKey("TMUX")) {
            found.add("TMUX is set, and a bare tmux command follows it to the server its caller sits in");
        }
        if (environment.containsKey("TMUX_PANE")) {
            found.add("TMUX_PANE is set, so this process claims to run inside a pane");
        }
        environment.keySet().stream()
                .filter(name -> name.startsWith("LIBTMUX_SOCKET"))
                .sorted()
                .forEach(name ->
                        found.add(name + " is set, and it selects a server for a configuration that names none"));
        if (sandbox == null || sandbox.isEmpty()) {
            found.add(SANDBOX_PROPERTY + " is not set, so no build task prepared a TMUX_TMPDIR for this process");
        } else if (!environment.containsKey("TMUX_TMPDIR")) {
            found.add("TMUX_TMPDIR is unset");
        } else if (!sandbox.equals(environment.get("TMUX_TMPDIR"))) {
            found.add("TMUX_TMPDIR is not the directory the build prepared for this task");
        }
        return List.copyOf(found);
    }

    /**
     * Refuses a process that {@link #findings} reports on.
     *
     * @param environment the process environment
     * @param sandbox the directory the build prepared for this task, or {@code null} when it did not
     * @throws IllegalStateException naming each finding, when there is one
     */
    static void require(Map<String, String> environment, @Nullable String sandbox) {
        List<String> found = findings(environment, sandbox);
        if (!found.isEmpty()) {
            throw new IllegalStateException(refusal(found));
        }
    }

    static String refusal(List<String> findings) {
        StringBuilder message =
                new StringBuilder("Refusing to run: this process could reach a tmux server it was not given.");
        findings.forEach(finding -> message.append("\n  - ").append(finding));
        message.append("\nRun the tests through ./gradlew, which clears TMUX, TMUX_PANE and LIBTMUX_SOCKET*")
                .append(" and exports a TMUX_TMPDIR of its own.");
        return message.toString();
    }
}
