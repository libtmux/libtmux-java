package io.github.libtmux;

import io.github.libtmux.query.Fields;

/** Typed fields of {@link Client}. */
public final class Client_ {

    private static final Fields.TextField<Client> NAME = Fields.text("client_name", Client::name);
    private static final Fields.ToOneRef<Client, Session> SESSION = Fields.toOne("session", Client::session);

    private Client_() {}

    /** The client's terminal name, which is how tmux addresses it. */
    public static Fields.TextField<Client> name() {
        return NAME;
    }

    /** The session this client was attached to, if any. */
    public static Fields.ToOneRef<Client, Session> session() {
        return SESSION;
    }
}
