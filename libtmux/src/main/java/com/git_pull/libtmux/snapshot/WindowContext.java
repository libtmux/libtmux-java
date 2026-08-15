package com.git_pull.libtmux.snapshot;

import com.git_pull.libtmux.SessionId;
import com.git_pull.libtmux.WindowId;
import com.git_pull.libtmux.WindowIndex;

/**
 * One tmux winlink: a window as it appears in one session, at one index.
 *
 * <p>The unit relations are keyed on. A window linked into two sessions has one {@link WindowId}
 * and two contexts, and tmux treats those two positions as distinct.
 *
 * @param session the session this link belongs to
 * @param index where the window sits in that session
 * @param window the underlying window, shared across links
 */
public record WindowContext(SessionId session, WindowIndex index, WindowId window) {}
