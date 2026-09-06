package io.github.libtmux.mcp;

import io.github.libtmux.Server;

/** What every tool and the capability resource on this connection share. */
final class Connection {

    private final Server server;
    private final Caller caller;
    private final ToolSurface surface;

    Connection(Server server, Caller caller, ToolSurface surface) {
        this.server = server;
        this.caller = caller;
        this.surface = surface;
    }

    static Connection to(Server server, ToolSurface surface) {
        return new Connection(server, Caller.of(server), surface);
    }

    Server server() {
        return server;
    }

    Caller caller() {
        return caller;
    }

    ToolSurface surface() {
        return surface;
    }

    /** One invocation on this connection. */
    Call call(java.util.Map<String, Object> arguments, Call.Progress progress) {
        return new Call(this, arguments, progress);
    }
}
