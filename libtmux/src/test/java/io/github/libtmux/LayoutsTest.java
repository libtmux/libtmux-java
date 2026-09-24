package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LayoutsTest {
    private static final TmuxVersion V3_7C = new TmuxVersion(3, 7, "c");
    private static final TmuxVersion V3_3A = new TmuxVersion(3, 3, "a");
    private static final TmuxVersion V3_8 = new TmuxVersion(3, 8, "");
    private static final TmuxVersion NEXT_3_9 = TmuxVersion.parse("next-3.9");

    private static final String JSON_LAYOUT = "{\"V\":2,\"L\":{\"t\":\"v\",\"w\":80,\"h\":24,\"i\":\"0\"}}";

    // ------------------------------------------------------------------------------ JSON

    @Test
    void aJsonLayoutIsAcceptedFromTheVersionThatWritesIt() {
        assertEquals(JSON_LAYOUT, Layouts.require(JSON_LAYOUT, V3_8));
        assertEquals(JSON_LAYOUT, Layouts.require(JSON_LAYOUT, NEXT_3_9));
    }

    /**
     * Before this round, {@code require(String, TmuxVersion)} had no JSON branch at all, so a JSON
     * layout was refused as an unrecognised name on every version, including ones that write it.
     */
    @Test
    void aJsonLayoutIsRefusedBelowTheVersionThatWritesIt() {
        UnsupportedTmuxVersionException refused =
                assertThrows(UnsupportedTmuxVersionException.class, () -> Layouts.require(JSON_LAYOUT, V3_7C));

        assertTrue(String.valueOf(refused.getMessage()).contains("3.8"), refused.getMessage());
    }

    @Test
    void malformedJsonShapeIsStillARefusalNotACrash() {
        assertThrows(IllegalArgumentException.class, () -> Layouts.require("{not json", V3_8));
    }

    // ------------------------------------------------------------------------ prefixes

    @Test
    void aUniquePrefixIsAcceptedOnEveryVersion() {
        assertEquals("tile", Layouts.require("tile", V3_3A));
        assertEquals("tile", Layouts.require("tile", V3_7C));
        assertEquals("even-h", Layouts.require("even-h", V3_3A));
        assertEquals("even-h", Layouts.require("even-h", V3_7C));
    }

    /**
     * {@code layout_set_lookup} is compiled from a fixed table: {@code main-horizontal-mirrored}
     * does not exist in a build older than 3.5, so {@code main-h} has exactly one candidate there.
     * From 3.5 on, the mirrored variant exists too and the same text is ambiguous — confirmed
     * against the matrix (3.3a: rc 0; 3.7c: {@code invalid layout: main-h}).
     */
    @Test
    void aPrefixThatIsUniqueOnlyOnAnOlderReleaseIsAcceptedThereAndRefusedLater() {
        assertEquals("main-h", Layouts.require("main-h", V3_3A));

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Layouts.require("main-h", V3_7C));
        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal"), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal-mirrored"), refused.getMessage());
    }

    @Test
    void aPrefixAmbiguousOnEveryVersionNamesTheCandidates() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Layouts.require("even-", V3_7C));

        assertTrue(String.valueOf(refused.getMessage()).contains("even-horizontal"), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains("even-vertical"), refused.getMessage());
    }

    @Test
    void anExactNameIsStillGatedByVersionEvenThoughItIsAlsoAPrefixOfAnother() {
        // main-horizontal is a prefix of main-horizontal-mirrored, but the exact name is not new.
        assertEquals("main-horizontal", Layouts.require("main-horizontal", V3_3A));

        assertThrows(UnsupportedTmuxVersionException.class, () -> Layouts.require("main-horizontal-mirrored", V3_3A));
    }

    @Test
    void theVersionlessOverloadAcceptsAPrefixTooButCannotNarrowByVersion() {
        assertEquals("tile", Layouts.require("tile"));
        assertEquals("even-h", Layouts.require("even-h"));

        assertThrows(IllegalArgumentException.class, () -> Layouts.require("not-a-real-layout"));
    }

    /**
     * No refusal may claim tmux does not know a layout when tmux does. {@code main-h} is a name
     * tmux 3.3a resolves without ambiguity (confirmed above); the versionless overload cannot narrow
     * by version, but it must still say the prefix is ambiguous, not that tmux has never heard of it.
     */
    @Test
    void theVersionlessOverloadNamesAnAmbiguousPrefixRatherThanCallingItUnknown() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> Layouts.require("main-h"));

        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal"), refused.getMessage());
        assertTrue(String.valueOf(refused.getMessage()).contains("main-horizontal-mirrored"), refused.getMessage());
        assertFalse(
                String.valueOf(refused.getMessage()).contains("not a tmux layout"),
                "tmux does know this prefix, just not unambiguously without a version: " + refused.getMessage());
    }

    // -------------------------------------------------------------------------------- builtIn

    @Test
    void builtInResolvesAPrefixToTheLayoutItDenotesOnThatVersion() {
        assertEquals(Optional.of(Layout.MAIN_HORIZONTAL), Layouts.builtIn("main-h", V3_3A));
        assertEquals(Optional.empty(), Layouts.builtIn("main-h", V3_7C), "ambiguous from 3.5 on");
        assertEquals(Optional.of(Layout.TILED), Layouts.builtIn("tile", V3_7C));
        assertEquals(Optional.empty(), Layouts.builtIn("not-a-layout", V3_7C));
    }

    // ---------------------------------------------------------------------- unchanged behaviour

    @Test
    void aSerializedLayoutWithTheWrongChecksumIsStillRefused() {
        // Same string CommandChainIntegrationTest already pins as invalid; a regression guard that
        // the JSON and prefix changes did not loosen the classic-form check.
        assertThrows(IllegalArgumentException.class, () -> Layouts.require("0000,80x24,0,0,1", V3_7C));
    }

    @ParameterizedTest
    @ValueSource(strings = {"t", "even-h", "b25d,80x24,0,0,0"})
    void syntaxValidationLeavesValidNamesAndGeometryForTmux(String layout) {
        assertEquals(layout, Layouts.require(layout));
    }

    @Test
    void namesUseTheRunningDaemonBeforeConsideringTheClient() {
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of("3.4"), List.of()));
                Server server = server(transport)) {
            assertEquals("main-horizontal", Layouts.require("main-h", server, 1));
            assertThrows(
                    UnsupportedTmuxVersionException.class,
                    () -> Layouts.require("main-horizontal-mirrored", server, 1));
            assertEquals(List.of("display-message", "display-message"), transport.commands);
        }
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of("3.7c"), List.of()));
                Server server = server(transport)) {
            IllegalArgumentException ambiguous =
                    assertThrows(IllegalArgumentException.class, () -> Layouts.require("main-h", server, 1));
            String message = String.valueOf(ambiguous.getMessage());
            assertTrue(message.contains("main-horizontal"), message);
            assertTrue(message.contains("main-horizontal-mirrored"), message);
            assertTrue(message.contains("tmux 3.5"), message);
            assertEquals("main-horizontal", Layouts.require("main-horizontal", server, 1));
            assertEquals("main-horizontal-mirrored", Layouts.require("main-horizontal-m", server, 1));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "no server running on /tmp/libtmux-java-test/absent",
                "error connecting to /tmp/libtmux-java-test/absent (No such file or directory)\n"
            })
    void onlyColdEndpointsUseTheSelectedClientVersion(String reason) {
        try (VersionTransport transport = new VersionTransport(new CommandResult(1, List.of(), List.of(reason)));
                Server server = server(transport)) {
            assertEquals("main-horizontal-mirrored", Layouts.require("main-horizontal-m", server, 1));
            assertEquals(List.of("display-message", "-V"), transport.commands);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "error connecting to /tmp/libtmux-java-test/private (Permission denied)",
                "protocol version mismatch (client 8, server 7)",
                "server exited unexpectedly",
                "no current target"
            })
    void probeFailuresDoNotFallBackToANewerClient(String reason) {
        try (VersionTransport transport = new VersionTransport(new CommandResult(1, List.of(), List.of(reason)));
                Server server = server(transport)) {
            LibTmuxException failure =
                    assertThrows(LibTmuxException.class, () -> Layouts.require("main-horizontal-mirrored", server, 1));
            assertTrue(String.valueOf(failure.getMessage()).contains(reason));
            assertEquals(List.of("display-message"), transport.commands);
        }
    }

    @Test
    void syntaxAndPaneCountsNeedNoVersionProbe() {
        try (VersionTransport transport =
                        new VersionTransport(new CommandResult(1, List.of(), List.of("must not run")));
                Server server = server(transport)) {
            assertEquals("tiled", Layouts.require("t", server, 2));
            assertThrows(IllegalArgumentException.class, () -> Layouts.require("b25d,80x24,0,0,0", server, 2));
            assertThrows(IllegalArgumentException.class, () -> Layouts.require("32d2,80x24,0,0{}", server, 1));
            assertTrue(transport.commands.isEmpty());
        }
    }

    @Test
    void numericFieldsRespectPositiveSizesAndSignedBounds() {
        assertEquals(serialized("80x24,0,0,2147483647"), Layouts.require(serialized("80x24,0,0,2147483647")));
        for (String body : List.of("4294967296x1,0,0", "1x1,0,4294967296", "1x1,0,0,4294967296")) {
            assertThrows(IllegalArgumentException.class, () -> Layouts.require(serialized(body)), body);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown"})
    void invalidDaemonVersionRepliesNeverSelectTheClient(String reported) {
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of(reported), List.of()));
                Server server = server(transport)) {
            assertThrows(LibTmuxException.class, () -> Layouts.require("main-horizontal-mirrored", server, 1));
            assertEquals(List.of("display-message"), transport.commands);
        }
    }

    @Test
    void deeplyNestedTreesHaveABoundedParser() {
        String body = "1x1,0,0{".repeat(256) + "1x1,0,0" + "}".repeat(256);
        assertEquals(serialized(body), Layouts.require(serialized(body)));
        assertThrows(IllegalArgumentException.class, () -> Layouts.require(serialized("1x1,0,0{" + body + "}")));
    }

    @Test
    void workspaceJsonPreflightCountsCellsAndRejectsNativeGrammarErrors() {
        String layout = "{\"V\":2,\"L\":{\"t\":\"p\",\"w\":80,\"h\":24,\"x\":0,\"y\":0,\"i\":0}}";
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of("3.8-rc"), List.of()));
                Server server = server(transport)) {
            assertEquals(layout, Layouts.require(layout, server, 1));
            assertThrows(IllegalArgumentException.class, () -> Layouts.require(layout, server, 2));
            for (String invalid : List.of(
                    layout.replace("\"V\":2", "\"V\":2e0"),
                    layout.replace("\"V\":2", "\"V\":2,\"V\":2"),
                    layout.replace("\"V\"", "\"\\u0056\""),
                    layout.replace("\"w\":80", "\"w\":0"),
                    layout.replace("\"i\":0", "\"i\":-1"),
                    layout.replace("\"i\":0", "\"i\":0,\"c\":[]"),
                    layout.replace("\"V\":2", "\"extra\":null,\"V\":2"))) {
                assertThrows(IllegalArgumentException.class, () -> Layouts.require(invalid, server, 1), invalid);
            }
        }
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of("3.7c"), List.of()));
                Server server = server(transport)) {
            assertThrows(UnsupportedTmuxVersionException.class, () -> Layouts.require(layout, server, 1));
        }
    }

    @Test
    void jsonCellsKeepUniqueIndexesAndBoundTheirGrammar() {
        var random = new java.util.Random(0x1a70);
        for (int sample = 0; sample < 64; sample++) {
            int width = random.nextInt(1, 5000);
            String first = "{\"t\":\"p\",\"w\":" + width + ",\"h\":24,\"x\":0,\"y\":0,\"i\":0,\"a\":true}";
            String second = first.replace("\"i\":0", "\"i\":1").replace("\"a\":true", "\"l\":0");
            String layout = "{\"V\":2,\"L\":{\"t\":\"h\",\"w\":" + (width * 2 + 1) + ",\"h\":24,\"x\":0,\"y\":0,\"c\":["
                    + first + "," + second + "]}}";
            assertEquals(2, JsonLayout.leaves(layout));
            assertThrows(IllegalArgumentException.class, () -> JsonLayout.leaves(layout.replace("\"i\":1", "\"i\":0")));
            assertThrows(
                    IllegalArgumentException.class, () -> JsonLayout.leaves(layout.replace("\"l\":0", "\"a\":true")));
        }
        String cell = "{\"t\":\"p\",\"w\":80,\"h\":24,\"x\":0,\"y\":0,\"i\":0}";
        for (String extra : List.of("null", "\"\"", "[0]", "9223372036854775808", "2.0", "01")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> JsonLayout.leaves("{\"V\":2,\"L\":" + cell + ",\"extra\":" + extra + "}"));
        }
        String prefix = "{\"V\":2,\"L\":" + cell + ",\"extra\":";
        assertEquals(1, JsonLayout.leaves(prefix + "{\"extra\":".repeat(198) + "{}" + "}".repeat(199)));
        String nested = prefix + "{\"extra\":".repeat(199) + "{}" + "}".repeat(200);
        assertThrows(IllegalArgumentException.class, () -> JsonLayout.leaves(nested));
    }

    private static String serialized(String body) {
        int checksum = 0;
        for (char value : body.toCharArray()) checksum = ((checksum >> 1) + ((checksum & 1) << 15) + value) & 0xffff;
        return "%04x,%s".formatted(checksum, body);
    }

    private static Server server(TmuxTransport transport) {
        return Server.using(
                ServerConfig.builder()
                        .endpoint(ServerEndpoint.namedSocket("layout-unit"))
                        .build(),
                transport);
    }

    private static final class VersionTransport implements TmuxTransport {
        private final CommandResult daemon;
        private final List<String> commands = new ArrayList<>();

        VersionTransport(CommandResult daemon) {
            this.daemon = daemon;
        }

        @Override
        public CommandResult execute(CommandRequest request) {
            List<String> command = request.commands().getFirst();
            commands.add(command.getFirst());
            if (command.getFirst().equals("display-message")) {
                assertEquals(
                        List.of(
                                "display-message",
                                "-p",
                                io.github.libtmux.format.RowFormat.of("pid", "version", "start_time")
                                        .template()),
                        command);
                if (!daemon.succeeded()) return daemon;
                return new CommandResult(
                        0,
                        List.of(String.join(
                                io.github.libtmux.format.RowFormat.of("pid", "version", "start_time")
                                        .separator(),
                                "4242",
                                String.join("\n", daemon.stdout()),
                                "1790000000")),
                        List.of());
            }
            assertEquals(List.of("-V"), command);
            return new CommandResult(0, List.of("tmux 3.7c"), List.of());
        }

        @Override
        public void close() {}
    }
}
