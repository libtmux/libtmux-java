package io.github.libtmux;

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
    void anAbsentSessionDoesNotListWindows() {
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

        String all = String.join("\n", sent);
        assertTrue(all.contains("list-sessions"), all);
        assertFalse(all.contains("list-windows"), all);
        assertFalse(all.contains("list-panes"), all);
    }
}
