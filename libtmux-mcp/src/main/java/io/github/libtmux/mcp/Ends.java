package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.transport.CommandResult;

/**
 * Tells why a read of a pane failed, so a wait can answer with it instead of with the failure.
 *
 * <p>A pane killed under a wait, and a server killed under it, both come back from tmux as a refused
 * command. Neither is the caller's mistake and neither is worth a retry: the wait is over, and what
 * it had read is the answer. Nothing here waits or counts down; the server either answers a probe
 * now or it does not, and a pane is either listed now or it is not.
 */
enum Ends {
    /** The server no longer answers. */
    SERVER_GONE,
    /** The server answers and the pane is no longer in it. */
    PANE_GONE;

    /**
     * Why a read failed.
     *
     * @param unread the failure of the read
     * @return the end the failure means
     * @throws LibTmuxException the failure itself, when the server still answers and still lists the
     *     pane, so it belongs to the caller
     */
    static Ends of(Pane pane, LibTmuxException unread) {
        // list-panes answers both questions and, unlike display-message, runs no
        // after-display-message hook, so asking costs the user's hooks nothing.
        CommandResult listing;
        try {
            listing = pane.server().cmd("list-panes", "-a", "-F", "#{pane_id}");
        } catch (CommandRejectedException refused) {
            return SERVER_GONE;
        }
        if (!listing.succeeded()) {
            return SERVER_GONE;
        }
        if (!listing.stdout().contains(pane.id().value())) {
            return PANE_GONE;
        }
        throw unread;
    }
}
