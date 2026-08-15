package com.git_pull.libtmux.snapshot;

import com.git_pull.libtmux.SessionId;
import java.util.Optional;

/**
 * A client as one capture saw it.
 *
 * @param name the client's terminal name, which is how tmux addresses it
 * @param session the session it was attached to, if any
 */
public record ClientState(String name, Optional<SessionId> session) {}
