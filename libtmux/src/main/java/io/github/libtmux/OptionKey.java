package io.github.libtmux;

import java.util.Objects;
import java.util.function.Function;

/**
 * A tmux option and the Java type its value reads as.
 *
 * <pre>{@code
 * session.options().set(OptionKey.HISTORY_LIMIT, 50_000);
 * int kept = session.options().get(OptionKey.HISTORY_LIMIT).orElseThrow();   // → 50000
 *
 * OptionKey<Boolean> bell = OptionKey.flag("visual-bell");
 * }</pre>
 *
 * <p>Declared by the caller rather than catalogued here. tmux has hundreds of options, adds them
 * between releases and changes a few of their types, so a closed catalogue would be wrong somewhere
 * on every release it did not track. A key says how to read one option; the name-and-string methods
 * on {@link Options} remain for anything else.
 *
 * <p>The constants are the ones whose type is the same on every supported release, checked against
 * tmux's own option table from 3.2a to 3.7c. {@code status} is not among the flags for that reason:
 * tmux reads it as a choice of {@code off}, {@code on} and a line count.
 *
 * @param <T> what the value reads as
 */
public final class OptionKey<T> {

    /** Lines of scrollback a pane keeps. A session option. */
    public static final OptionKey<Integer> HISTORY_LIMIT = number("history-limit");

    /** The index a session's first window gets. A session option. */
    public static final OptionKey<Integer> BASE_INDEX = number("base-index");

    /** How long tmux waits after an escape to see whether a key sequence follows. A server option. */
    public static final OptionKey<Integer> ESCAPE_TIME = number("escape-time");

    /** Whether the mouse is captured. A session option. */
    public static final OptionKey<Boolean> MOUSE = flag("mouse");

    /** Whether a window renames itself after what its pane runs. A window option. */
    public static final OptionKey<Boolean> AUTOMATIC_RENAME = flag("automatic-rename");

    /** Whether keys typed into one pane go to every pane in the window. A window or pane option. */
    public static final OptionKey<Boolean> SYNCHRONIZE_PANES = flag("synchronize-panes");

    private final String name;
    private final Function<String, T> read;
    private final Function<T, String> write;

    private OptionKey(String name, Function<String, T> read, Function<T, String> write) {
        this.name = Objects.requireNonNull(name, "name");
        this.read = read;
        this.write = write;
    }

    /** An option read as the text tmux reports, which suits a string, a choice, a style or a colour. */
    public static OptionKey<String> text(String name) {
        return new OptionKey<>(name, Function.identity(), Function.identity());
    }

    /** An option tmux holds as a number. */
    public static OptionKey<Integer> number(String name) {
        return new OptionKey<>(name, reported -> parseNumber(name, reported), value -> Integer.toString(value));
    }

    /** An option tmux holds as a flag, which it reports as {@code on} or {@code off}. */
    public static OptionKey<Boolean> flag(String name) {
        return new OptionKey<>(name, reported -> parseFlag(name, reported), value -> value ? "on" : "off");
    }

    /** The option's name, as tmux spells it. */
    public String name() {
        return name;
    }

    T read(String reported) {
        return read.apply(reported);
    }

    String write(T value) {
        return write.apply(Objects.requireNonNull(value, "value"));
    }

    private static Integer parseNumber(String name, String reported) {
        try {
            return Integer.valueOf(reported.strip());
        } catch (NumberFormatException notANumber) {
            throw new LibTmuxException(
                    "tmux reported option " + name + " as '" + reported + "', which is not a number", notANumber);
        }
    }

    private static Boolean parseFlag(String name, String reported) {
        return switch (reported.strip()) {
            case "on" -> true;
            case "off" -> false;
            default ->
                throw new LibTmuxException(
                        "tmux reported option " + name + " as '" + reported + "', which is not on or off");
        };
    }

    @Override
    public String toString() {
        return "OptionKey[" + name + "]";
    }
}
