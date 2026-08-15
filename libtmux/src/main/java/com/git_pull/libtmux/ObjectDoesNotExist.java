package com.git_pull.libtmux;

/**
 * A live server does not have the object a handle addresses.
 *
 * <p>Distinct from a transport or command failure: the server answered, and the answer was that
 * this session, window or pane is gone. A caller can act on that — re-capture, or stop — where an
 * unreachable server calls for something else entirely.
 */
public final class ObjectDoesNotExist extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    public ObjectDoesNotExist(String message) {
        super(message);
    }
}
