package com.git_pull.libtmux;

/**
 * What a client was looking at.
 *
 * <p>All three are present together or not at all. A client attached to a session is looking at
 * exactly one window of it and one pane of that window; an attachment holding only some of those
 * would describe a state tmux does not have.
 *
 * @param session the session the client is attached to
 * @param activeWindow that session's active window
 * @param activePane that window's active pane, which is what the client's keystrokes reach
 */
public record ClientAttachment(Session session, Window activeWindow, Pane activePane) {}
