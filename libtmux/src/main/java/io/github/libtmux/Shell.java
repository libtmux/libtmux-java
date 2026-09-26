package io.github.libtmux;

import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import io.github.libtmux.exception.UnsupportedFeatureException;
import java.util.List;
import java.util.Objects;
import kotlin.annotations.jvm.ReadOnly;

/**
 * A shell command run by tmux, and a tmux command chosen by a shell command's exit status.
 *
 * <p>tmux expands {@code #(...)} in these strings before a shell sees them. Pass an interpolated
 * value through {@link TmuxFormats#literal} unless that expansion is what you want.
 */
public final class Shell {

    /**
     * tmux lost {@code run-shell}'s output in 3.3a and found it again in 3.5. The command exists on
     * every release, so {@code list-commands} cannot tell the gap from the working releases.
     */
    private static final TmuxVersion OUTPUT_LOST = new TmuxVersion(3, 3, "");

    private static final TmuxVersion OUTPUT_FOUND = new TmuxVersion(3, 5, "");

    private final Server server;

    Shell(Server server) {
        this.server = server;
    }

    /** Runs a shell command for its effect. Nothing is claimed about what it printed. */
    @Operation(Kind.MUTATION)
    public void run(String command) {
        Objects.requireNonNull(command, "command");
        server.run(List.of("run-shell", "--", command));
    }

    /**
     * Runs a shell command and answers with what it printed.
     *
     * @throws UnsupportedFeatureException on tmux 3.3a and 3.4, which run the command and report
     *     nothing
     */
    @ReadOnly
    @Operation(Kind.MUTATION)
    public List<String> capturing(String command) {
        Objects.requireNonNull(command, "command");
        TmuxVersion running = server.version();
        if (running.atLeast(OUTPUT_LOST) && !running.atLeast(OUTPUT_FOUND)) {
            throw new UnsupportedFeatureException(
                    "reading what run-shell printed is broken between tmux 3.3a and 3.4, and this server runs "
                            + running);
        }
        return server.run(List.of("run-shell", "--", command)).stdout();
    }

    /**
     * Runs one tmux command when a shell command succeeds.
     *
     * @param condition a shell command, judged by its exit status
     * @param whenTrue the tmux command to run when the condition succeeds
     */
    @Operation(Kind.MUTATION)
    public void choose(String condition, String whenTrue) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(whenTrue, "whenTrue");
        server.run(List.of("if-shell", "--", condition, whenTrue));
    }

    /**
     * Runs one tmux command or another, according to whether a shell command succeeds.
     *
     * @param whenFalse the tmux command to run when the condition does not succeed
     */
    @Operation(Kind.MUTATION)
    public void choose(String condition, String whenTrue, String whenFalse) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(whenTrue, "whenTrue");
        Objects.requireNonNull(whenFalse, "whenFalse");
        server.run(List.of("if-shell", "--", condition, whenTrue, whenFalse));
    }
}
