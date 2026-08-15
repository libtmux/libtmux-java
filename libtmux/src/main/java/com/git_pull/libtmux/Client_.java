package com.git_pull.libtmux;

import com.git_pull.libtmux.query.EntityMetamodel;
import com.git_pull.libtmux.query.Fields;

/** Typed fields of {@link Client}. */
public final class Client_ extends EntityMetamodel {

    private Client_() {}

    /** The client's terminal name, which is how tmux addresses it. */
    public static Fields.TextField<Client> name() {
        return text("client_name", Client::name);
    }

    /** The session this client was attached to, if any. */
    public static Fields.ToOneRef<Client, Session> session() {
        return toOne("session", Client::session);
    }
}
