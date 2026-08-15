package com.git_pull.libtmux;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * How commands travel to tmux.
 *
 * <p>Chosen once on a {@link ServerConfig} and applied to everything that server does. It changes
 * only the carrying: the same handles answer the same questions with the same types, whichever mode
 * is in force. {@code ExecutionModeConformanceTest} runs identical scenarios through each and
 * asserts identical outcomes.
 *
 * <pre>{@code
 * ServerConfig config = ServerConfig.builder()
 *         .endpoint(ServerEndpoint.socketPath(socket))
 *         .mode(ExecutionMode.CONTROL)
 *         .build();
 * }</pre>
 *
 * <p><strong>Batching and chaining are deliberately absent.</strong> They are often described
 * alongside these, but they are not ways of carrying a command: {@link Server#batch()} groups
 * commands into one request and {@link Server#chain()} lets each step act on what the last one
 * made, and both compose over whichever mode is in force rather than replacing it.
 *
 * <p>Neither could be a mode even if it were wanted. This is a blocking API:
 * {@code server.windows()} has to return a list by the time it returns, so a carrier that held the
 * command back to group it would have nothing to give. Putting them on this switch would also tell
 * a reader they were alternatives to these, when in fact they run under any of them.
 */
public enum ExecutionMode {

    /**
     * One tmux process per command.
     *
     * <p>What the tmux binary does when a shell runs it. No state is held between commands, so
     * nothing can be stale and nothing needs a session to exist first. Costs a process each time.
     */
    DIRECT,

    /**
     * One persistent tmux client, in control mode, carrying every command.
     *
     * <p>Costs one process for the life of the server rather than one per command, and is the only
     * mode that can also stream pane output as it happens.
     *
     * <p>tmux control mode attaches to a session, so this cannot carry the command that creates the
     * first one. Until a session exists the carrier falls back to {@link #DIRECT}, and attaches as
     * soon as there is something to attach to. A caller never has to know: the fallback is invisible
     * apart from the process count.
     */
    CONTROL,

    /**
     * A process per command, waited for on a virtual thread rather than on the caller's own.
     *
     * <p>Nothing becomes asynchronous: the call still blocks until tmux answers. What changes is
     * which thread is parked meanwhile. A caller running on a small pool of platform threads keeps
     * those free while tmux is slow, for the price of one virtual thread per command.
     *
     * <p>A caller already on a virtual thread should not select this: every carrier here is safe to
     * call from one, because the transports block on a lock rather than inside {@code synchronized}.
     * This exists for the caller who cannot choose their own threads.
     */
    VIRTUAL;

    /** The JVM flag naming a mode: {@code -Dlibtmux.mode=control}. */
    public static final String PROPERTY = "libtmux.mode";

    /** The environment variable naming a mode: {@code LIBTMUX_MODE=control}. */
    public static final String VARIABLE = "LIBTMUX_MODE";

    /**
     * The mode a JVM flag or an environment variable asks for, if either does.
     *
     * <p>Lets an operator move a program onto another carrier without editing or rebuilding it,
     * which is the only reason this exists: a mode changes cost and nothing else, so trying one is
     * meant to be cheap. {@link #PROPERTY} wins over {@link #VARIABLE}, a flag passed to this JVM
     * being more deliberate than an environment it merely inherited.
     *
     * <p>An unreadable value is refused rather than ignored. Falling back to a default would leave
     * an operator believing a carrier was in force that never was, and the whole point of the
     * variable is that the answer looks the same either way.
     *
     * <p>Reading is separated from the sources for the reason {@link TmuxEnvironment} gives: the
     * precedence is then testable without a JVM flag or a process environment to arrange.
     *
     * @param properties typically {@code System.getProperties()}
     * @param environment typically {@code System.getenv()}
     * @return the mode asked for, or empty when neither source says anything
     * @throws IllegalArgumentException if either source names something that is not a mode
     */
    public static Optional<ExecutionMode> of(Properties properties, Map<String, String> environment) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        Optional<ExecutionMode> fromProperty = read(PROPERTY, properties.getProperty(PROPERTY));
        return fromProperty.isPresent() ? fromProperty : read(VARIABLE, environment.get(VARIABLE));
    }

    /** One source's answer: absent, a mode, or a refusal naming what to fix. */
    private static Optional<ExecutionMode> read(String source, @Nullable String value) {
        // An unset variable is commonly spelled as an empty one, and means the same thing here.
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String wanted = value.strip();
        for (ExecutionMode mode : values()) {
            if (mode.name().equalsIgnoreCase(wanted)) {
                return Optional.of(mode);
            }
        }
        throw new IllegalArgumentException(
                source + "=" + value + " is not a mode; expected one of " + Arrays.toString(values()));
    }
}
