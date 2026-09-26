package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.format.Tokens;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NarrowLookupTest {

    @Test
    void anAbsentSessionCostsOneFencedRead() {
        List<String> sent = new ArrayList<>();
        RowFormat identity = RowFormat.of("pid", "version", "start_time");
        String row = String.join(identity.separator(), "4242", "3.6", "1790000000");
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                String flat = request.commands().stream()
                        .flatMap(List::stream)
                        .reduce("", (left, right) -> left + " " + right);
                sent.add(flat);
                if (flat.contains("display-message") && !flat.contains("list-sessions")) {
                    return new CommandResult(0, List.of(row), List.of());
                }
                if (flat.contains("list-sessions")) {
                    return new CommandResult(0, List.of(Tokens.perProcess() + ":0"), List.of());
                }
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(ServerConfig.builder().build(), transport)) {
            assertTrue(server.session("missing").isEmpty());
        }

        assertEquals(2, sent.size(), String.join("\n", sent));
        assertTrue(sent.get(1).contains("list-sessions"), sent.get(1));
    }

    /** A filtered read costs the identity and one fenced read, however many sessions match. */
    @Test
    void matchingSessionsAreReadInOneFencedCall() {
        String sep = RowFormat.of("x").separator();
        List<CommandRequest> requests = new ArrayList<>();
        TmuxTransport transport = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                requests.add(request);
                return GroupedTmux.execute(request, 4242L, argv -> {
                    String template = argv.get(argv.indexOf("-F") + 1);
                    return switch (argv.get(0)) {
                        case "display-message" ->
                            new CommandResult(0, List.of("4242" + sep + "3.6" + sep + "1790000000"), List.of());
                        case "list-panes" ->
                            template.contains("pane_id")
                                    ? new CommandResult(
                                            0, List.of(pane("$1", "@1", "%1"), pane("$2", "@2", "%2")), List.of())
                                    : new CommandResult(0, List.of("$1", "$2"), List.of());
                        case "list-sessions" ->
                            new CommandResult(
                                    0,
                                    List.of(
                                            String.join(sep, "$1", "one", "0", "1"),
                                            String.join(sep, "$2", "two", "0", "1")),
                                    List.of());
                        case "list-windows" ->
                            new CommandResult(0, List.of(window("$1", "@1"), window("$2", "@2")), List.of());
                        default -> new CommandResult(0, List.of(), List.of());
                    };
                });
            }

            @Override
            public void close() {}
        };

        try (Server server = Server.using(ServerConfig.builder().build(), transport)) {
            assertEquals(2, server.panes(Pane_.command().is("nvim")).size());
        }

        assertEquals(2, requests.size(), requests.toString());
    }

    /** A pane lookup costs what a snapshot does, the identity and one fenced read, and no more. */
    @Test
    void aPaneIsFoundInOneFencedRead() {
        List<CommandRequest> requests = new ArrayList<>();
        try (Server server = Server.using(ServerConfig.builder().build(), twoSessions(requests))) {
            assertEquals("%2", server.pane(new PaneId("%2")).orElseThrow().id().value());
        }

        assertEquals(2, requests.size(), requests.toString());
        String read = requests.get(1).commands().toString();
        assertTrue(read.contains("#{W:#{P:#{?#{==:#{pane_id},%2},1,}}}"), read);
    }

    @Test
    void aLookupOnAClosedServerSaysItIsClosed() {
        Server server = Server.using(ServerConfig.builder().build(), twoSessions(new ArrayList<>()));
        server.close();

        assertThrows(IllegalStateException.class, () -> server.pane(new PaneId("%2")));
        assertThrows(IllegalStateException.class, () -> server.session("one"));
        assertThrows(IllegalStateException.class, () -> server.session(new SessionId("$1")));
    }

    /** Two sessions of one pane each; it answers every listing in full, whatever its filter. */
    private static TmuxTransport twoSessions(List<CommandRequest> requests) {
        String sep = RowFormat.of("x").separator();
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                requests.add(request);
                return GroupedTmux.execute(request, 4242L, argv -> {
                    String template = argv.get(argv.indexOf("-F") + 1);
                    return switch (argv.get(0)) {
                        case "display-message" ->
                            new CommandResult(0, List.of("4242" + sep + "3.6" + sep + "1790000000"), List.of());
                        case "list-panes" ->
                            template.contains("pane_id")
                                    ? new CommandResult(
                                            0, List.of(pane("$1", "@1", "%1"), pane("$2", "@2", "%2")), List.of())
                                    : new CommandResult(0, List.of("$1", "$2"), List.of());
                        case "list-sessions" ->
                            new CommandResult(
                                    0,
                                    List.of(
                                            String.join(sep, "$1", "one", "0", "1"),
                                            String.join(sep, "$2", "two", "0", "1")),
                                    List.of());
                        case "list-windows" ->
                            new CommandResult(0, List.of(window("$1", "@1"), window("$2", "@2")), List.of());
                        default -> new CommandResult(0, List.of(), List.of());
                    };
                });
            }

            @Override
            public void close() {}
        };
    }

    private static String window(String session, String window) {
        return String.join(
                RowFormat.of("x").separator(),
                session,
                window,
                window.substring(1),
                "w",
                "1",
                "1",
                "1",
                "80",
                "24",
                "layout");
    }

    private static String pane(String session, String window, String pane) {
        return String.join(
                RowFormat.of("x").separator(),
                session,
                window,
                window.substring(1),
                pane,
                "0",
                "1",
                "nvim",
                "80",
                "24",
                "0",
                "0",
                "t",
                "/tmp",
                "11",
                "1",
                "1",
                "1",
                "1");
    }

    @Test
    void aSafePaneExpressionIsSentAsAFormat() {
        List<String> sent = commands();
        try (Server server = Server.using(ServerConfig.builder().build(), answering(sent))) {
            assertTrue(server.panes(Pane_.command().is("nvim")).isEmpty());
        }
        String all = String.join("\n", sent);
        assertTrue(all.contains("-f"), all);
        assertTrue(all.contains("#{==:#{pane_current_command},nvim}"), all);
    }

    @Test
    void aSafeSessionExpressionIsSentAsAFormat() {
        List<String> sent = commands();
        try (Server server = Server.using(ServerConfig.builder().build(), answering(sent))) {
            assertTrue(server.sessions(Session_.name().is("build")).isEmpty());
        }
        String all = String.join("\n", sent);
        assertTrue(all.contains("list-sessions"), all);
        assertTrue(all.contains("#{==:#{session_name},build}"), all);
    }

    @Test
    void aSafeWindowExpressionIsSentAsAFormat() {
        List<String> sent = commands();
        try (Server server = Server.using(ServerConfig.builder().build(), answering(sent))) {
            assertTrue(server.windows(Window_.name().is("editor")).isEmpty());
        }
        String all = String.join("\n", sent);
        assertTrue(all.contains("list-windows"), all);
        assertTrue(all.contains("#{==:#{window_name},editor}"), all);
    }

    @Test
    void aWindowRelationStaysLocal() {
        List<String> sent = commands();
        try (Server server = Server.using(ServerConfig.builder().build(), answering(sent))) {
            assertTrue(server.windows(Window_.panes().any(Pane_.command().is("nvim")))
                    .isEmpty());
        }
        assertFalse(String.join("\n", sent).contains("-f"));
    }

    @Test
    void anUnsafePaneExpressionStaysLocal() {
        List<String> sent = commands();
        try (Server server = Server.using(ServerConfig.builder().build(), answering(sent))) {
            assertTrue(server.panes(Pane_.command().is("a,b")).isEmpty());
        }
        assertFalse(String.join("\n", sent).contains("-f"));
    }

    private static List<String> commands() {
        return new ArrayList<>();
    }

    private static TmuxTransport answering(List<String> sent) {
        RowFormat identity = RowFormat.of("pid", "version", "start_time");
        String row = String.join(identity.separator(), "4242", "3.6", "1790000000");
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                String flat = request.commands().stream()
                        .flatMap(List::stream)
                        .reduce("", (left, right) -> left + " " + right);
                sent.add(flat);
                if (flat.contains("display-message") && !flat.contains("list-")) {
                    return new CommandResult(0, List.of(row), List.of());
                }
                return new CommandResult(0, List.of(Tokens.perProcess() + ":0"), List.of());
            }

            @Override
            public void close() {}
        };
    }
}
