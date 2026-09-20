package io.github.libtmux;

/**
 * Text this JVM cannot hand to tmux intact, with no route left to send it by.
 *
 * <p>A child process receives its arguments as bytes, and the JVM encodes them with the platform's
 * native encoding — which is the locale's, decided before {@code main} runs. In a locale that is
 * not UTF-8 that encoding cannot carry most of Unicode, so tmux would receive {@code ?} where a
 * caller wrote {@code é}. The process transport therefore sends such a command over tmux's standard
 * input instead, which this library encodes itself, and this is raised only when that route is
 * already taken — a command that reads standard input — or when the text is in the endpoint, the
 * binary or socket path, which nothing but an argument can carry.
 *
 * <p>Only the environment removes the limit: start the JVM with {@code LC_ALL} or {@code LANG}
 * naming a UTF-8 locale. {@code -Dsun.jnu.encoding} and {@code -Dfile.encoding} do not, because the
 * native encoding is read before either is applied.
 *
 * <p>ASCII text is unaffected on every locale, and so is everything tmux sends back: a reply is
 * decoded as the UTF-8 tmux wrote rather than through the platform's encoding.
 */
public final class UnencodableTextException extends LibTmuxException {

    private static final long serialVersionUID = 1L;

    public UnencodableTextException(String message) {
        super(message);
    }
}
