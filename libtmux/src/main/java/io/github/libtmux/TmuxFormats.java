package io.github.libtmux;

import java.util.Objects;

/**
 * Values handed to a tmux argument position that expands formats.
 *
 * <p>tmux expands {@code #{...}} and {@code #(...)} in many argument positions, and {@code #(...)}
 * runs a shell command. For the arguments this library composes itself — a window name, a pane
 * title, a working directory — it applies {@link #literal} at the construction boundary, so a
 * caller's {@code #} stays a {@code #}.
 *
 * <p>It cannot do that for an argument the caller composes: {@link Pane#pipeTo},
 * {@link Window#displayPopup}, {@link Server#runShell}, {@link Server#runShellCapturing} and
 * {@link Server#ifShell} all take a whole shell command, where format expansion is a documented
 * tmux feature a caller may want — {@code #{pane_id}} in a filename, for instance. A caller
 * interpolating an untrusted value into one of those needs {@link #literal} on that value, which is
 * why this is public.
 *
 * <p>Shell quoting does not substitute for it. tmux expands the format <em>before</em> the shell
 * sees the string, so a {@code #(...)} inside single quotes still runs. Measured on tmux 3.7d, with
 * the interpolated value quoted as a careful caller would quote it: {@code run-shell} executed it,
 * and {@code pipe-pane -O} executed it as soon as the pane produced output.
 */
public final class TmuxFormats {

    private TmuxFormats() {}

    /**
     * Makes one caller value literal at one construction boundary.
     *
     * <p>Doubling {@code #} is tmux's own escape for it, so the value arrives as itself.
     *
     * @param value a value to be read as text rather than expanded
     * @return the value with every {@code #} doubled
     */
    public static String literal(String value) {
        return Objects.requireNonNull(value, "value").replace("#", "##");
    }
}
