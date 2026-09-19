package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Pane;
import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.ServerEndpoint;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.UnsupportedTmuxVersionException;
import io.github.libtmux.Window;
import io.github.libtmux.batch.BatchResult;
import io.github.libtmux.batch.OperationOutcome;
import io.github.libtmux.batch.OperationResult;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Chaining, and the property it is built on.
 *
 * <p>tmux moves its own current target as a group runs, so a step can act on what the previous step
 * made without naming it. Without that, building a window and typing into its second pane costs a
 * round trip per step just to learn ids.
 */
@ExtendWith(TmuxExtension.class)
final class CommandChainIntegrationTest {

    @Test
    void eachStepActsOnWhatTheLastOneMade(Server server) throws Exception {
        BatchResult result = server.chain()
                .newWindow("chained")
                .splitLeftRight()
                .sendLine("echo chained-landed-here")
                .run();

        assertTrue(result.succeeded(), result.toString());
        Window built = server.windows().stream()
                .filter(window -> window.name().equals("chained"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, built.panes().size(), "the split applied to the window the chain had just made");

        List<Pane> panes = built.panes();
        assertTrue(
                Await.until(
                        () -> panes.get(1).capture().stream().anyMatch(line -> line.contains("chained-landed-here"))),
                "the keys went to the pane the split produced, not to the one the chain started from");
    }

    @Test
    void aLineThatIsAKeyNameStaysLiteralInsideAChain(Server server) throws Exception {
        assertTrue(server.chain()
                .newWindow("literal-line")
                .sendLine("Enter() { printf 'literal-chain-%s\\n' enter; }")
                .sendLine("echo defined-the-function")
                .run()
                .succeeded());
        Pane pane = server.windows().stream()
                .filter(window -> window.name().equals("literal-line"))
                .findFirst()
                .orElseThrow()
                .panes()
                .get(0);
        // The shell has to have read the definition before the name is used. A chain is one
        // invocation, so without this the name is typed before anything is reading for it.
        assertTrue(
                Await.until(() -> pane.capture().stream().anyMatch(line -> line.contains("defined-the-function"))),
                "the shell never read the definition");

        BatchResult result = server.chain()
                .sendLine("clear")
                .sendLine("Enter")
                .sendLine("-R")
                .sendLine("printf 'literal-chain-%s\\n' semicolon;")
                .run();

        assertTrue(result.succeeded(), result.toString());
        assertTrue(
                Await.until(() -> pane.capture().stream().anyMatch(line -> line.contains("literal-chain-enter"))),
                "Enter was pressed instead of typed");
        assertTrue(
                Await.until(() -> pane.capture().stream().anyMatch(line -> line.contains("literal-chain-semicolon"))),
                "a trailing semicolon became a command-group separator");
    }

    @Test
    void theWholeChainIsOneInvocation(Server server) {
        BatchResult result = server.chain()
                .newWindow("one")
                .newWindow("two")
                .newWindow("three")
                .run();

        assertEquals(3, result.operations().size());
        assertEquals(4, server.windows().size(), "the fixture window plus three");
    }

    @Test
    void aChainThatFailsSaysWhichStepAndWhichNeverRan(Server server) {
        BatchResult result = server.chain()
                .newWindow("made")
                .then("select-pane", "-t", "=missing")
                .newWindow("never")
                .run();

        assertEquals(
                List.of(OperationOutcome.COMPLETE, OperationOutcome.FAILED, OperationOutcome.SKIPPED),
                result.operations().stream().map(OperationResult::outcome).toList());
        assertTrue(
                server.windows().stream().noneMatch(window -> window.name().equals("never")),
                "tmux discarded the rest of the group");
    }

    /**
     * A layout name tmux does not recognise ends the whole server on 3.3a, taking every session on
     * the socket with it. The chain refuses it before anything is dispatched.
     */
    @Test
    void anUnknownLayoutIsRefusedBeforeAnythingRuns(Server server) {
        assertThrows(
                IllegalArgumentException.class,
                () -> server.chain().newWindow("safe").arrange("not-a-real-layout"),
                "the check has to happen while building the chain, not when running it");
        assertThrows(
                IllegalArgumentException.class,
                () -> server.chain().newWindow("safe").arrange("0000,80x24,0,0,1"),
                "a serialized layout with the wrong checksum is just as unsafe");

        assertEquals(1, server.windows().size(), "and nothing was dispatched");
    }

    /**
     * A mirrored preset is a name tmux 3.4 and earlier does not know, which is the same fatal path
     * on 3.3a as any other unrecognised layout. The chain asks the running tmux, as
     * {@code Window.selectLayout(Layout)} already did.
     */
    @Test
    void aMirroredLayoutIsRefusedBelowTmux35(Server server) {
        if (server.version().atLeast(new TmuxVersion(3, 5, ""))) {
            BatchResult result = server.chain()
                    .newWindow("mirrored")
                    .splitLeftRight()
                    .arrange("main-vertical-mirrored")
                    .run();
            assertTrue(result.succeeded(), result.toString());
        } else {
            assertThrows(
                    UnsupportedTmuxVersionException.class,
                    () -> server.chain().newWindow("safe").arrange("main-vertical-mirrored"));
        }
        assertTrue(server.isAlive(), "the server survives either way");
    }

    @Test
    void aRecognisedLayoutIsApplied(Server server) {
        BatchResult result = server.chain()
                .newWindow("arranged")
                .splitLeftRight()
                .arrange("even-horizontal")
                .run();

        assertTrue(result.succeeded(), result.toString());
    }

    /**
     * {@code Window#layout()}'s own doc says its string "can be handed straight back to
     * select-layout"; on tmux 3.8+ that string is JSON, and {@code arrange} shares {@link
     * io.github.libtmux.Layouts#require(String, TmuxVersion)} with {@code Window#applyLayout}, which
     * already handled it. Exercised against a fake daemon reporting each version rather than a real
     * one, since the compatibility matrix this project tests against tops out at 3.7c and a JSON
     * layout is refused there on shape alone — see {@code LayoutsTest} for the version-gated logic
     * itself; this pins the real {@link Server#chain()} call site to it.
     */
    @Test
    void arrangeAcceptsAJsonLayoutOnlyFromTheVersionThatWritesIt() {
        String json = "{\"V\":2,\"L\":{\"t\":\"v\",\"w\":80,\"h\":24,\"i\":\"0\"}}";

        try (Server tooOld = fakeServer("3.7c")) {
            assertThrows(
                    UnsupportedTmuxVersionException.class, () -> tooOld.chain().arrange(json));
        }
        try (Server current = fakeServer("3.8")) {
            // Does not throw: the chain accepts it exactly as Window#applyLayout already did.
            current.chain().arrange(json);
        }
    }

    /** A server that answers its own identity without a real tmux process behind it. */
    private static Server fakeServer(String version) {
        String separator = RowFormat.of("field").separator();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                if (request.commands().get(0).get(0).equals("display-message")) {
                    return new CommandResult(0, List.of(String.join(separator, "4242", version)), List.of());
                }
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
        return Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("command-chain-test"))
                        .build(),
                transport);
    }
}
