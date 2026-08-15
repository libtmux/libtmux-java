package com.git_pull.libtmux;

/**
 * Why a wait on a tmux channel ended.
 *
 * <p>An enum rather than a boolean, and rather than nothing at all, because tmux's own
 * {@code wait-for} cannot distinguish these: it exits successfully both when the channel is
 * signalled and when the server dies underneath the waiter.
 */
public enum WakeReason {
    /** The channel was signalled. */
    SIGNALLED,
    /** Nothing signalled the channel before the caller's deadline. */
    TIMED_OUT,
    /**
     * The server is gone. tmux reports this as a successful wake, so it is checked for rather than
     * believed; a caller cannot rely on anything the wait was guarding.
     */
    SERVER_GONE
}
