package io.github.libtmux;

import java.util.Objects;

/**
 * What the running tmux does with a session or window name.
 *
 * <p>Exactly tmux 3.7 refuses a name containing {@code .} or {@code :}; 3.7a and later keep it and
 * earlier releases store {@code _} in its place. Refusing it here, before dispatch, keeps a batch or
 * a workspace build from running the commands ahead of the one tmux would reject.
 */
public final class Names {

    private static final TmuxVersion REFUSES = new TmuxVersion(3, 7, "");

    private Names() {}

    /**
     * As {@link #require(String, TmuxVersion)}, for the daemon this server talks to, read without
     * running a command that fires a user's hooks.
     *
     * @throws IllegalArgumentException if the daemon refuses a name with {@code .} or {@code :}
     */
    public static String require(String name, Server server) {
        Objects.requireNonNull(name, "name");
        return name.indexOf('.') < 0 && name.indexOf(':') < 0 ? name : require(name, server.versionForCreation());
    }

    /**
     * Returns the name when the running tmux accepts it.
     *
     * @param name the session or window name to be sent
     * @param running the version of the daemon that would receive it
     * @throws IllegalArgumentException if the running tmux refuses a name with {@code .} or {@code :}
     */
    public static String require(String name, TmuxVersion running) {
        Objects.requireNonNull(name, "name");
        if (running.sameRelease(REFUSES) && (name.indexOf('.') >= 0 || name.indexOf(':') >= 0)) {
            throw new IllegalArgumentException(
                    "tmux 3.7 refuses '.' and ':' in session and window names (3.7a and later keep them): " + name);
        }
        return name;
    }
}
