package com.git_pull.libtmux.mcp;

/**
 * One pane, described for a model.
 *
 * @param id the pane's stable id, which is what other tools take as a target
 * @param window the window it lives in
 * @param session the session that window belongs to
 * @param command the command tmux reports running in it
 * @param active whether it is its window's active pane
 */
public record PaneSummary(String id, String window, String session, String command, boolean active) {}
