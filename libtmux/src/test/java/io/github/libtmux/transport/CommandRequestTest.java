package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A request is a value: it cannot change under its caller, and it does not leak what it carries. */
final class CommandRequestTest {

    private static final List<String> ENDPOINT = List.of("tmux", "-S", "/run/user/1000/tmux/default");
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void theCommandLineIsTheEndpointFollowedByTheArguments() {
        CommandRequest request = new CommandRequest(ENDPOINT, List.of("list-panes", "-a"), SECOND);

        assertEquals(List.of("tmux", "-S", "/run/user/1000/tmux/default", "list-panes", "-a"), request.commandLine());
    }

    @Test
    void argumentsStaySeparateElementsSoNothingIsEverShellParsed() {
        CommandRequest request = new CommandRequest(ENDPOINT, List.of("send-keys", "echo one; echo two"), SECOND);

        assertEquals(
                "echo one; echo two",
                request.commandLine().get(request.commandLine().size() - 1),
                "a semicolon inside one element must not become a command separator");
    }

    @Test
    void mutatingTheListsAfterConstructionCannotChangeTheRequest() {
        List<String> endpoint = new ArrayList<>(List.of("tmux"));
        List<String> argv = new ArrayList<>(List.of("list-panes"));
        CommandRequest request = new CommandRequest(endpoint, argv, SECOND);

        endpoint.add("-S");
        argv.add("-a");

        assertEquals(List.of("tmux"), request.endpoint());
        assertEquals(List.of("list-panes"), request.argv());
    }

    @Test
    void anEndpointWithoutAnExecutableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest(List.of(), List.of("ls"), SECOND));
    }

    @Test
    void aTimeoutThatCannotElapseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest(ENDPOINT, List.of(), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> new CommandRequest(ENDPOINT, List.of(), Duration.ofSeconds(-1)));
    }

    /**
     * NullAway already rejects these calls for an annotated caller, which is why they are
     * suppressed here rather than written normally. The runtime check still has to exist: Kotlin
     * platform types, reflection and unannotated Java all reach this constructor with no such check.
     */
    @Test
    @SuppressWarnings("NullAway")
    void nullsAreProgrammerErrorsNotTmuxFailures() {
        assertThrows(NullPointerException.class, () -> new CommandRequest(ENDPOINT, List.of(), null));
        assertThrows(NullPointerException.class, () -> new CommandRequest(null, List.of(), SECOND));
        assertThrows(NullPointerException.class, () -> new CommandRequest(ENDPOINT, null, SECOND));
    }

    /**
     * argv carries pane content and socket paths, and this value reaches logs and failed
     * assertions, so its rendering exposes counts only.
     */
    @Test
    void toStringExposesNeitherSocketPathsNorPaneContent() {
        CommandRequest request =
                new CommandRequest(ENDPOINT, List.of("send-keys", "-t", "%1", "export TOKEN=hunter2"), SECOND);

        String rendered = request.toString();

        assertFalse(rendered.contains("hunter2"), "pane content must not reach a log line: " + rendered);
        assertFalse(rendered.contains("/run/user"), "a socket path must not reach a log line: " + rendered);
        assertEquals(
                "CommandRequest[argumentCount=4, timeout=PT1S]",
                rendered,
                "counts and the timeout are the whole diagnostic");
    }
}
