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
 * {@link Window#displayPopup}, {@link Shell#run}, {@link Shell#capturing} and {@link Shell#choose}
 * all take a whole shell command, where format expansion is a documented
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

    /**
     * The names tmux may have kept for one it was given, the given name first.
     *
     * <p>Measured across the supported range: 3.2a through 3.6 store {@code .} and {@code :} as
     * {@code _}; 3.7d doubles a trailing backslash; 3.2a stores a control character as a C escape
     * or three octal digits, which 3.7 rejects instead.
     */
    static java.util.List<String> storedNames(String name) {
        java.util.LinkedHashSet<String> forms = new java.util.LinkedHashSet<>();
        forms.add(name);
        String underscored = name.replace('.', '_').replace(':', '_');
        forms.add(underscored);
        forms.add(escaped(name));
        forms.add(escaped(underscored));
        return java.util.List.copyOf(forms);
    }

    private static String escaped(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            switch (character) {
                case '\\' -> out.append(index == name.length() - 1 ? "\\\\" : "\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case 0x07 -> out.append("\\a");
                case 0x0b -> out.append("\\v");
                default -> {
                    if (character < 0x20 || character == 0x7f) {
                        out.append('\\').append(String.format(java.util.Locale.ROOT, "%03o", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }
}
