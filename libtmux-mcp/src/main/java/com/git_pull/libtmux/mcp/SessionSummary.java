package com.git_pull.libtmux.mcp;

import java.util.List;

/**
 * One session, described for a model.
 *
 * @param name the session name
 * @param id the session's stable id
 * @param attached whether a client is attached
 * @param windows the window names, in tmux's order
 */
public record SessionSummary(String name, String id, boolean attached, List<String> windows) {

    public SessionSummary {
        windows = List.copyOf(windows);
    }
}
