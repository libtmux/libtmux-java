package com.git_pull.libtmux;

/**
 * A tmux session id, such as {@code $0}.
 *
 * <p>Stable for the life of the session, unlike a session name, which a user can change at any
 * time. A separate type from {@link WindowId} and {@link PaneId} because tmux accepts any of them
 * as {@code -t} syntax: passing the wrong one is not an error tmux reports, it simply addresses
 * nothing.
 *
 * @param value the id including its {@code $} sigil
 */
public record SessionId(String value) {

    public SessionId {
        TargetIds.require(value, '$', "session");
    }

    @Override
    public String toString() {
        return value;
    }
}
