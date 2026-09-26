package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.UnencodableTextException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.ProcessTransport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the library does on a JVM whose locale is not UTF-8, which is the default in most container
 * images.
 *
 * <p>Two separate things go wrong there and this pins both. Outbound, the JVM encodes a child's
 * arguments with the platform encoding, so {@code é} would leave as {@code ?} and tmux would name a
 * session after it; the library sends such commands over standard input instead, which it encodes
 * itself. Inbound, tmux replaces every non-ASCII character in a reply with {@code _} for a client
 * it does not believe reads UTF-8, so a name or a directory read back would be a different string
 * from the one tmux holds.
 *
 * <p>Run by the {@code localeTest} task, which forks a JVM under {@code LC_ALL=C}. The first test
 * here refuses to let the rest pass vacuously on a developer's UTF-8 machine.
 */
@Tag("locale")
@ExtendWith(TmuxExtension.class)
final class NonUtf8LocaleIntegrationTest {

    /** Not a letter this JVM can encode, and one whose UTF-8 form is two bytes. */
    private static final String ACCENTED = "café";

    @Test
    void thisLaneReallyRunsWithoutUtf8() {
        String encoding = System.getProperty("sun.jnu.encoding", System.getProperty("native.encoding", ""));

        assertNotEquals(
                "UTF-8",
                encoding,
                "this lane exists to exercise a non-UTF-8 JVM; under UTF-8 every assertion below passes for"
                        + " the wrong reason");
    }

    /**
     * The defect this pins: text this JVM could not encode as an argument was refused outright, so a
     * container on the default locale could not name a session {@code café} at all — and asking
     * whether one existed threw while finding it by name worked. Commands that cannot travel as
     * arguments now travel over standard input, which this library writes as UTF-8 itself.
     */
    @Test
    void textThisJvmCannotEncodeAsAnArgumentStillReachesTmuxIntact(Server server) {
        Session created = server.newSession(ACCENTED);

        assertEquals(ACCENTED, created.name(), "tmux holds the name it was given");
        assertTrue(server.hasSession(ACCENTED), "and asking for it by name agrees with finding it");
        assertEquals(ACCENTED, server.session(ACCENTED).orElseThrow().name());
        assertTrue(
                server.sessions().stream().noneMatch(session -> session.name().equals("caf?")),
                "nothing was created under a mangled name");
    }

    /** Every kind of write, not only a name: each takes a different command to tmux. */
    @Test
    void everyWriteCarriesTextThisJvmCannotEncode(Server server) {
        Pane pane = server.panes().get(0);

        Pane retitled = pane.retitle(ACCENTED);
        server.buffers().set("accented", ACCENTED);
        server.environment().set("ACCENTED", ACCENTED);
        server.globalOptions().set("@accented", ACCENTED);

        assertEquals(ACCENTED, retitled.title());
        assertEquals(ACCENTED, server.buffers().show("accented"));
        assertEquals(Optional.of(ACCENTED), server.environment().get("ACCENTED"));
        assertEquals(Optional.of(ACCENTED), server.globalOptions().get("@accented"));
    }

    /**
     * Standard input has room for one thing. When a command already reads it there is no second
     * route, and the text is refused rather than corrupted — naming the character and the fix, and
     * keeping the text itself out of a message that may reach a log.
     */
    @Test
    void textWithNoRouteLeftIsRefusedRatherThanQuietlyCorrupted(Server server) {
        try (ProcessTransport transport = new ProcessTransport(1)) {
            UnencodableTextException refused = assertThrows(
                    UnencodableTextException.class,
                    () -> transport.execute(CommandRequest.of(
                            server.config().endpointCommand(),
                            List.of("load-buffer", "-b", ACCENTED, "-"),
                            Duration.ofSeconds(5),
                            "contents")));

            String reported = String.valueOf(refused.getMessage());
            assertTrue(reported.contains("U+00E9"), "the message names the character that cannot travel");
            assertTrue(reported.contains("LC_ALL"), "and the environment change that fixes it");
            assertFalse(reported.contains(ACCENTED), "and keeps the text itself out of a message bound for a log");
        }
    }

    @Test
    void asciiIsUnaffected(Server server) {
        Session created = server.newSession("plain");

        assertEquals("plain", created.name());
    }

    @Test
    void aNameThisClientCouldNotHaveWrittenStillReadsBackIntact(
            Server server, TmuxSocketPath socket, @TempDir Path scratch) throws IOException {
        runThroughTmux(server, scratch, "new-session -d -s " + ACCENTED);

        Optional<Session> found = server.session(ACCENTED);

        assertTrue(found.isPresent(), "tmux holds the name it was given, so a UTF-8 client must read it back");
        assertEquals(ACCENTED, found.orElseThrow().name());
    }

    @Test
    void aPaneInADirectoryThisJvmCannotNameDoesNotFailTheCapture(Server server, @TempDir Path scratch)
            throws IOException {
        Path directory = scratch.resolve("home");
        Files.createDirectories(directory);
        // Made through tmux rather than through Files, which cannot name it from this JVM either.
        runThroughTmux(
                server,
                scratch,
                "run-shell 'mkdir -p \"" + directory + "/" + ACCENTED + "\"'",
                "new-session -d -s inside -c '" + directory + "/" + ACCENTED + "'");

        List<Pane> panes = server.panes();

        Pane inside = panes.stream()
                .filter(pane -> pane.currentPathText().endsWith(ACCENTED))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the capture lost the pane whose directory it could not name"));
        assertEquals(directory + "/" + ACCENTED, inside.currentPathText());
        LibTmuxException unrepresentable = assertThrows(LibTmuxException.class, inside::currentPath);
        assertTrue(
                String.valueOf(unrepresentable.getMessage()).contains("LC_ALL"),
                "asking for a Path is where it fails, and it says what to do about it");
    }

    /**
     * Runs tmux commands this JVM could not pass as arguments.
     *
     * <p>tmux reads a command file as bytes, so UTF-8 written into one survives a JVM that could not
     * have put the same text in an argv. That is the only way this lane can set up what it reads
     * back — and it is not how the library works, which is the point of the test above it.
     */
    private static void runThroughTmux(Server server, Path scratch, String... commands) throws IOException {
        Path file = scratch.resolve("commands.tmux");
        Files.write(file, String.join("\n", commands).getBytes(StandardCharsets.UTF_8));

        assertTrue(
                server.cmd("source-file", file.toString()).succeeded(),
                "the fixture's own commands have to run before anything is asserted about them");
    }
}
