package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds a {@link Call} the way the protocol layer would, without the protocol.
 *
 * <p>What a tool does to tmux is worth testing against real tmux; attaching it to a transport is
 * not, and a test that had to speak JSON-RPC to check an exit status would be testing the SDK.
 */
final class TestCalls {

    private TestCalls() {}

    /** A call on a server this process is not running inside, which is the case under test. */
    static Call on(Server server, Object... pairs) {
        Map<String, Object> arguments = new HashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            arguments.put(pairs[index].toString(), pairs[index + 1]);
        }
        Connection connection = new Connection(
                server, Caller.nowhere(), Safety.DESTRUCTIVE, java.util.concurrent.ConcurrentHashMap.newKeySet());
        return new Call(connection, arguments, Call.Progress.SILENT);
    }

    /** A call that believes it is running in {@code paneId}, for the guards that turn on then. */
    static Call asCaller(Server server, String paneId, Object... pairs) {
        Call plain = on(server, pairs);
        Map<String, String> environment = Map.of(
                "TMUX", socket(server) + ",1," + server.sessions().get(0).id().value(), "TMUX_PANE", paneId);
        Connection connection = new Connection(
                server,
                Caller.of(server, environment),
                Safety.DESTRUCTIVE,
                java.util.concurrent.ConcurrentHashMap.newKeySet());
        return new Call(connection, plain.arguments(), Call.Progress.SILENT);
    }

    private static String socket(Server server) {
        return server.cmd("display-message", "-p", "#{socket_path}").stdout().get(0);
    }
}
