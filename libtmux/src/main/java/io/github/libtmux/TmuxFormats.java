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

    private static final TmuxVersion DOLLAR_ESCAPED = new TmuxVersion(3, 4, "");

    private static final java.util.regex.Pattern ESCAPED_DOLLAR =
            java.util.regex.Pattern.compile("\\\\(?=\\$[A-Za-z_{])");

    /**
     * Text as tmux held it, from a line it printed.
     *
     * <p>tmux 3.4 alone puts a backslash before every {@code $} that an ASCII letter, {@code _}, or
     * <code>{</code> follows, in everything it prints: formats, listings, and {@code show-options
     * -v}. 3.5 stopped ({@code 692ce59b}). Each escape is exactly one backslash in that position, so
     * removing it restores the text on 3.4 and changes nothing elsewhere.
     */
    static String printed(String line, TmuxVersion version) {
        return version.equals(DOLLAR_ESCAPED) ? ESCAPED_DOLLAR.matcher(line).replaceAll("") : line;
    }

    /** As {@link #printed(String, TmuxVersion)}, for every line. */
    static java.util.List<String> printed(java.util.List<String> lines, TmuxVersion version) {
        return version.equals(DOLLAR_ESCAPED)
                ? lines.stream().map(line -> printed(line, version)).toList()
                : lines;
    }

    /** 3.7 refuses {@code .} and {@code :} in a name, 3.7a keeps them, and earlier releases store {@code _}. */
    private static final TmuxVersion DELIMITERS_KEPT_SINCE = new TmuxVersion(3, 7, "");

    /**
     * The names to look for when a caller names a session: the one this release stores for it, then
     * the name exactly as given, which is what {@link Session#name} already reports.
     *
     * <p>Measured on every release in the matrix: tmux doubles each backslash, and 3.2a through 3.6
     * store {@code .} and {@code :} as {@code _} and a control character as a C escape or three
     * octal digits. 3.7 and later refuse control characters. The stored form is an escaping, so it
     * names at most one session; only a name that is itself some other name's stored form is
     * ambiguous, and the stored form wins.
     */
    static java.util.List<String> storedNames(String name, TmuxVersion version) {
        String mapped = version.atLeast(DELIMITERS_KEPT_SINCE)
                ? name
                : name.replace('.', '_').replace(':', '_');
        java.util.LinkedHashSet<String> forms = new java.util.LinkedHashSet<>();
        forms.add(vis(mapped));
        forms.add(name);
        return java.util.List.copyOf(forms);
    }

    /** tmux's {@code vis(3)} with {@code VIS_CSTYLE | VIS_OCTAL | VIS_TAB | VIS_NL}. */
    private static String vis(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
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
