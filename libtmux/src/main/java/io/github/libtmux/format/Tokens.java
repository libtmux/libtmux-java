package io.github.libtmux.format;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The token this process marks its own text with.
 *
 * <p>tmux gives no character a special meaning in a name or in output, so any fixed marker is
 * something a user can produce. One random token per process makes a collision mean naming a window
 * with the exact value this run generated.
 *
 * <p>Hexadecimal, because the token is spliced into tmux format templates and into regular
 * expressions and must be inert in both.
 */
public final class Tokens {

    private static final String PROCESS = generate();

    private Tokens() {}

    /** A token generated once per process, shared by everything that needs to mark its own text. */
    public static String perProcess() {
        return PROCESS;
    }

    private static String generate() {
        byte[] token = new byte[16];
        new SecureRandom().nextBytes(token);
        return HexFormat.of().formatHex(token);
    }
}
