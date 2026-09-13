package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LayoutsTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "t",
                "even-h",
                "8a08,1x1,0,0{39x24,0,0,0,40x24,40,0,1}",
                "79f5,80x24,0,0{39x23,0,0,0,40x24,40,0,1}"
            })
    void syntaxValidationLeavesValidNamesAndGeometryForTmux(String layout) {
        assertEquals(layout, Layouts.require(layout));
    }

    @Test
    void namesUseTheRunningDaemonBeforeConsideringTheClient() {
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of("3.4"), List.of()));
                Server server = server(transport)) {
            assertEquals("main-horizontal", Layouts.require("main-h", server, 1));
            assertThrows(UnsupportedTmuxVersion.class, () -> Layouts.require("main-horizontal-mirrored", server, 1));
            assertEquals(List.of("display-message", "display-message"), transport.commands);
        }
        try (VersionTransport transport = new VersionTransport(new CommandResult(0, List.of("3.7c"), List.of()));
                Server server = server(transport)) {
            assertThrows(IllegalArgumentException.class, () -> Layouts.require("main-h", server, 1));
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
    void numericFieldsUseUnsigned32BitBounds() {
        assertEquals(
                serialized("4294967295x0,0,0,4294967295"), Layouts.require(serialized("4294967295x0,0,0,4294967295")));
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
                assertEquals(List.of("display-message", "-p", "#{version}"), command);
                return daemon;
            }
            assertEquals(List.of("-V"), command);
            return new CommandResult(0, List.of("tmux 3.7c"), List.of());
        }

        @Override
        public void close() {}
    }
}
