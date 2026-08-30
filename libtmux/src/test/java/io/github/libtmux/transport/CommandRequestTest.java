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
        CommandRequest request = CommandRequest.of(ENDPOINT, List.of("list-panes", "-a"), SECOND);

        assertEquals(List.of("tmux", "-S", "/run/user/1000/tmux/default", "list-panes", "-a"), request.commandLine());
    }

    @Test
    void argumentsStaySeparateElementsSoNothingIsEverShellParsed() {
        CommandRequest request = CommandRequest.of(ENDPOINT, List.of("send-keys", "echo one; echo two"), SECOND);

        assertEquals(
                "echo one; echo two",
                request.commandLine().get(request.commandLine().size() - 1),
                "a semicolon inside one element must not become a command separator");
    }

    /** tmux would otherwise take the semicolon as the end of the command and drop it from the value. */
    @Test
    void anArgumentEndingInASemicolonIsEscapedForTmuxsArgvParser() {
        CommandRequest request = CommandRequest.of(ENDPOINT, List.of("rename-window", "build;"), SECOND);

        assertEquals(List.of("rename-window", "build\\;"), request.commandLine().subList(3, 5));
        assertEquals(List.of("rename-window", "build;"), request.commands().get(0), "the request keeps what was meant");
    }

    @Test
    void severalCommandsAreSeparatedByABareSemicolon() {
        CommandRequest request = new CommandRequest(
                ENDPOINT, List.of(List.of("kill-window", "-t", "@1"), List.of("list-windows")), SECOND, "");

        assertEquals(
                List.of("kill-window", "-t", "@1", ";", "list-windows"),
                request.commandLine().subList(3, 8));
    }

    @Test
    void mutatingTheListsAfterConstructionCannotChangeTheRequest() {
        List<String> endpoint = new ArrayList<>(List.of("tmux"));
        List<String> argv = new ArrayList<>(List.of("list-panes"));
        CommandRequest request = CommandRequest.of(endpoint, argv, SECOND);

        endpoint.add("-S");
        argv.add("-a");

        assertEquals(List.of("tmux"), request.endpoint());
        assertEquals(List.of(List.of("list-panes")), request.commands());
    }

    @Test
    void anEndpointWithoutAnExecutableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandRequest.of(List.of(), List.of("ls"), SECOND));
    }

    @Test
    void aRequestWithNothingToRunIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandRequest.of(ENDPOINT, List.of(), SECOND));
        assertThrows(IllegalArgumentException.class, () -> new CommandRequest(ENDPOINT, List.of(), SECOND, ""));
    }

    @Test
    void aTimeoutThatCannotElapseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandRequest.of(ENDPOINT, List.of("ls"), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandRequest.of(ENDPOINT, List.of("ls"), Duration.ofSeconds(-1)));
    }

    /**
     * NullAway already rejects these calls for an annotated caller, which is why they are
     * suppressed here rather than written normally. The runtime check still has to exist: Kotlin
     * platform types, reflection and unannotated Java all reach this constructor with no such check.
     */
    @Test
    @SuppressWarnings("NullAway")
    void nullsAreProgrammerErrorsNotTmuxFailures() {
        assertThrows(NullPointerException.class, () -> CommandRequest.of(ENDPOINT, List.of("ls"), null));
        assertThrows(NullPointerException.class, () -> CommandRequest.of(null, List.of("ls"), SECOND));
        assertThrows(NullPointerException.class, () -> CommandRequest.of(ENDPOINT, null, SECOND));
    }

    /**
     * A command carries pane content and socket paths, and this value reaches logs and failed
     * assertions, so its rendering exposes counts only.
     */
    @Test
    void toStringExposesNeitherSocketPathsNorPaneContent() {
        CommandRequest request =
                CommandRequest.of(ENDPOINT, List.of("send-keys", "-t", "%1", "export TOKEN=hunter2"), SECOND);

        String rendered = request.toString();

        assertFalse(rendered.contains("hunter2"), "pane content must not reach a log line: " + rendered);
        assertFalse(rendered.contains("/run/user"), "a socket path must not reach a log line: " + rendered);
        assertEquals(
                "CommandRequest[commandCount=1, timeout=PT1S]",
                rendered,
                "counts and the timeout are the whole diagnostic");
    }
}
