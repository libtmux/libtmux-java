package com.git_pull.libtmux.snapshot;

import com.git_pull.libtmux.SessionId;

/**
 * A session as one capture saw it.
 *
 * @param id the session's stable id
 * @param name the session name, which a user may change at any time
 * @param attached whether a client was attached to it
 * @param windows how many windows it contained
 */
public record SessionState(SessionId id, String name, boolean attached, int windows) {}
