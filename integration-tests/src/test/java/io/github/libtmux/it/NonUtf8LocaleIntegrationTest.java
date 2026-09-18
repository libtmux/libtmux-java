package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.UnencodableTextException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.junit5.TmuxSocketPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * arguments with the platform encoding, so {@code é} leaves as {@code ?} and tmux names a session
 * after it. Inbound, tmux replaces every non-ASCII character in a reply with {@code _} for a client
 * it does not believe reads UTF-8, so a name or a directory read back is a different string from
 * the one tmux holds.
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

    @Test
    void textThisJvmCannotEncodeIsRefusedRatherThanQuietlyCorrupted(Server server) {
        UnencodableTextException refused =
                assertThrows(UnencodableTextException.class, () -> server.newSession(ACCENTED));

        String reported = String.valueOf(refused.getMessage());
        assertTrue(reported.contains("U+00E9"), "the message names the character that cannot travel");
        assertTrue(reported.contains("LC_ALL"), "and the environment change that fixes it");
        assertFalse(
                reported.contains(ACCENTED),
                "a tmux argument carries session names and pane content, so the text itself stays out of a"
                        + " message that reaches a log");
        assertTrue(
                server.sessions().stream().noneMatch(session -> session.name().startsWith("caf")),
                "nothing was created under a mangled name");
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
