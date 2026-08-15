package com.git_pull.libtmux;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which layout names are safe to hand to tmux.
 *
 * <p>Public because every path that can reach select-layout needs it, not only the one that reads
 * a workspace file. This check exists because an unrecognised layout name does not produce an error on every tmux:
 * on 3.3a it crashes the server, taking down every session on that socket, including ones this
 * program never created. A workspace file is user-supplied text, so the name has to be checked
 * before it is dispatched rather than after tmux has had it.
 *
 * <p>tmux accepts either one of its five named layouts or a serialized layout string, which begins
 * with a four-digit checksum followed by a comma.
 */
public final class Layouts {

    private static final Set<String> NAMED =
            Set.of("even-horizontal", "even-vertical", "main-horizontal", "main-vertical", "tiled");

    private static final Pattern SERIALIZED = Pattern.compile("^[0-9a-f]{4},.+");

    private Layouts() {}

    /**
     * Returns the layout unchanged, having checked tmux will recognise it.
     *
     * @throws IllegalArgumentException if tmux would not recognise the name, which on some versions
     *     is not a recoverable error
     */
    public static String require(String layout) {
        if (NAMED.contains(layout) || SERIALIZED.matcher(layout).matches()) {
            return layout;
        }
        throw new IllegalArgumentException(
                "not a tmux layout: '" + layout + "'; expected one of " + NAMED + " or a serialized layout");
    }
}
