package com.git_pull.libtmux.control;

import com.git_pull.libtmux.PaneId;

/**
 * Terminal output tmux pushed without being asked.
 *
 * @param pane the pane that produced it
 * @param data the bytes as text, with tmux's octal escapes already decoded
 */
public record PaneOutput(PaneId pane, String data) {

    /** Identifies the pane only: the data is terminal content. */
    @Override
    public String toString() {
        return "PaneOutput[" + pane + ", characters=" + data.length() + "]";
    }
}
