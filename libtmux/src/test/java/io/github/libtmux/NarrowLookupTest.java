package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        RowFormat identity = RowFormat.of("pid", "version");
        String row = "4242" + identity.separator() + "3.6";
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
        RowFormat identity = RowFormat.of("pid", "version");
        String row = "4242" + identity.separator() + "3.6";
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
