package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.junit5.TmuxExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A read that failed against a read that found nothing.
 *
 * <p>The distinction the rest of this library is built on, applied to the four reads that answered
 * outside it. "No such session" is an answer; "this socket cannot be opened" and "this binary is
 * not tmux" are not, and reporting either as an empty result is how a misconfigured endpoint reads
 * as an idle one — a caller then creates what is already there, or waits for a server it can never
 * reach.
 *
 * <p>Three endpoints stand for the three cases: a socket nothing serves, a socket in a directory
 * this user cannot enter, and a binary that exits nonzero and says nothing at all.
 */
@ExtendWith(TmuxExtension.class)
final class FailedReadIntegrationTest {

    private static Server at(Path socket, String binary) {
        return Server.open(ServerConfig.builder()
                .binary(binary)
                .endpoint(ServerEndpoint.socketPath(socket))
                .build());
    }

    private static Path unreachableSocket(Path scratch) throws IOException {
        Path locked = Files.createDirectory(scratch.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        return locked.resolve("s");
    }

    /**
     * A binary that is not tmux: exits with {@code status} and prints nothing, on every platform.
     * {@code /bin/false} answers this on Linux but not macOS, which keeps it only under {@code
     * /usr/bin}.
     */
    private static Path exitingBinary(Path scratch, int status) throws IOException {
        Path binary = scratch.resolve("not-tmux");
        Files.writeString(binary, "#!/bin/sh\nexit " + status + "\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        return binary;
    }

    // ------------------------------------------------------------- a read that found nothing

    @Test
    void anAbsentDaemonIsItsOwnAnswer(@TempDir Path scratch) {
        try (Server server = at(scratch.resolve("nobody-home"), "tmux")) {
            assertThrows(ServerUnavailableException.class, () -> server.hasSession("build"));
            assertThrows(ServerUnavailableException.class, () -> server.keys().list());
            assertThrows(
                    ServerUnavailableException.class, () -> server.commands().list());
            assertThrows(
                    ServerUnavailableException.class,
                    () -> server.globalOptions().get("history-limit"));
            assertThrows(ServerUnavailableException.class, server::requireAlive);
        }
    }

    /**
     * Every method, not the handful that remembered to check. The classification used to live at each
     * site that cared, so seven reads and every mutation reported a missing daemon as an ordinary
     * failure carrying tmux's own wording — the one thing the migration notes tell a caller it can
     * catch instead of matching on a message.
     */
    @Test
    void aMutationOnAnAbsentDaemonSaysSoToo(@TempDir Path scratch) {
        try (Server server = at(scratch.resolve("nobody-home"), "tmux")) {
            assertThrows(ServerUnavailableException.class, () -> server.killSession("build"));
            assertThrows(
                    ServerUnavailableException.class,
                    () -> server.globalOptions().set("@x", "1"));
            assertThrows(
                    ServerUnavailableException.class, () -> server.environment().set("K", "v"));
            assertThrows(
                    ServerUnavailableException.class, () -> server.buffers().set("b", "v"));
            assertThrows(
                    ServerUnavailableException.class, () -> server.keys().bind("F12", List.of("display-message", "x")));
            assertThrows(
                    ServerUnavailableException.class, () -> server.messageLog().lines());
            assertThrows(ServerUnavailableException.class, () -> server.hooks().all());
            assertThrows(
                    ServerUnavailableException.class, () -> server.buffers().list());
            assertThrows(
                    ServerUnavailableException.class, () -> server.environment().all());
        }
    }

    /**
     * What "no daemon" is recognised by, asserted against the tmux each lane actually runs.
     *
     * <p>The distinction rests on substrings of tmux's own stderr, because tmux offers no exit code
     * for it. A release that reworded this would turn every absent-daemon answer into an ordinary
     * failure, silently, and the migration notes tell callers to branch on exactly that difference.
     */
    @Test
    void tmuxStillWordsAnAbsentDaemonTheWayThisLibraryReadsIt(@TempDir Path scratch) {
        try (Server server = at(scratch.resolve("nobody-home"), "tmux")) {
            assertThrows(ServerUnavailableException.class, () -> server.hasSession("build"));

            String said =
                    String.join("\n", server.cmd("has-session", "-t", "=build").stderr());
            assertTrue(
                    said.contains("no server running") || said.contains("(No such file or directory)"),
                    "this tmux words an absent daemon in a way the library no longer recognises: " + said);
        }
    }

    @Test
    void readingKeysDoesNotStartADaemonToAnswerWith(@TempDir Path scratch) {
        Path socket = scratch.resolve("untouched");

        try (Server server = at(socket, "tmux")) {
            assertThrows(ServerUnavailableException.class, () -> server.keys().list());
        }

        assertFalse(
                Files.exists(socket),
                "tmux answers list-keys from its own tables and starts a server to do it; a read must not");
    }

    @Test
    void aMissingSessionOnALiveServerIsStillFalse(Server server) {
        assertFalse(server.hasSession("no-such-session"));
    }

    /**
     * A name tmux has no option for, rather than an unset {@code @user} one: tmux accepts any
     * {@code @} name as a user option, and 3.2a answers an unset one with an empty value where 3.7
     * calls it invalid. Only a name that is not an option at all means the same thing on every
     * supported release.
     */
    @Test
    void anOptionTmuxDoesNotKnowIsStillEmpty(Server server) {
        assertEquals(Optional.empty(), server.globalOptions().get("not-an-option"));
    }

    // ------------------------------------------------------------------- a read that failed

    @Test
    void aSocketThisUserCannotOpenIsNotAnEmptyServer(@TempDir Path scratch) throws IOException {
        Path socket = unreachableSocket(scratch);
        try (Server server = at(socket, "tmux")) {
            assertReadsFailRatherThanAnswer(server);
        } finally {
            Files.setPosixFilePermissions(socket.getParent(), PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aBinaryThatIsNotTmuxIsNotAnEmptyServer(@TempDir Path scratch) throws IOException {
        Path notTmux = exitingBinary(scratch, 1);
        try (Server server = at(scratch.resolve("s"), notTmux.toString())) {
            assertReadsFailRatherThanAnswer(server);

            LibTmuxException reported = assertThrows(LibTmuxException.class, server::sessions);
            String message = String.valueOf(reported.getMessage());
            assertTrue(message.contains("exit 1"), "the message carries how tmux ended: " + message);
            assertTrue(message.contains(notTmux.toString()), "and which binary it was: " + message);
        }
    }

    /**
     * Every read raises, and none of them claims the daemon is simply absent — that is a different
     * fact, and the one a caller acts on by starting a server.
     */
    private static void assertReadsFailRatherThanAnswer(Server server) {
        assertFailedRead("hasSession", () -> server.hasSession("build"));
        assertFailedRead("listKeys", () -> server.keys().list());
        assertFailedRead("listCommands", () -> server.commands().list());
        assertFailedRead("options.get", () -> server.globalOptions().get("history-limit"));
        assertFailedRead("requireAlive", server::requireAlive);
        assertFailedRead("sessions", server::sessions);
    }

    private static void assertFailedRead(String what, org.junit.jupiter.api.function.Executable read) {
        LibTmuxException raised = assertThrows(LibTmuxException.class, read, what + " answered instead of raising");
        assertFalse(
                raised instanceof ServerUnavailableException,
                what + " reported a server it could not reach as one that is not running: " + raised.getMessage());
    }
}
