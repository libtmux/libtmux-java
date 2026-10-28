package io.github.libtmux.internal;

import io.github.libtmux.ServerConfig;
import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.support.Capabilities;
import io.github.libtmux.internal.support.Versions;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.ProcessTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Qualifies a carrier-owned endpoint before the first control effect. */
public final class ControlAcquisition implements AutoCloseable {
    private static final String FORMAT = "#{version},#{pid},#{start_time},'#{s/'/'\\\\''/:socket_path}'";
    private final ControlCarrier.PinnedSocket pin;
    private final Identity identity;
    private final List<String> command;
    private final Duration budget;
    private final long began;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ControlAcquisition(
            ControlCarrier.PinnedSocket pin, Identity identity, List<String> command, Duration budget, long began) {
        this.pin = pin;
        this.identity = identity;
        this.command = command;
        this.budget = budget;
        this.began = began;
    }

    public static ControlAcquisition acquire(ControlCarrier carrier, ServerConfig config, Duration budget) {
        long began = System.nanoTime();
        // The hook-free probe decides first: a daemon below the floor must not run a user's
        // after-display-message hook for the identity read that follows.
        TmuxVersion version = Versions.requireCarrier(carrier, config.endpointCommand(), budget);
        requireSupported(version);
        Identity identity = read(carrier, config.endpointCommand(), remaining(budget, began, false));
        if (!identity.version().equals(version.toString())) {
            throw new TargetGoneException("the tmux endpoint changed during control acquisition");
        }
        ControlCarrier.PinnedSocket pin;
        try {
            pin = carrier.pinSocket(identity.socket(), remaining(budget, began, false));
        } catch (UnsupportedOperationException failure) {
            throw new UnsupportedFeatureException(
                    "control acquisition requires endpoint pinning in the carrier's realm");
        } catch (IOException failure) {
            throw new DispatchException.Failed(
                    "cannot retain the control socket", DispatchOutcome.NOT_DISPATCHED, failure);
        }
        try {
            List<String> command = new ArrayList<>(config.endpointCommand());
            int selectorEnd = command.size() - (config.configFile().isPresent() ? 2 : 0);
            int selectorStart = selectorEnd - config.endpoint().flags().size();
            command.subList(selectorStart, selectorEnd).clear();
            command.addAll(selectorStart, List.of("-S", pin.socketPath()));
            command.add(1, "-N");
            ControlAcquisition acquisition = new ControlAcquisition(pin, identity, List.copyOf(command), budget, began);
            Identity retained = read(carrier, acquisition.command(), acquisition.remaining(false));
            if (!identity.equals(retained))
                throw new TargetGoneException("the tmux endpoint changed during control acquisition");
            return acquisition;
        } catch (RuntimeException failure) {
            try {
                pin.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Whether control clients are qualified on this daemon: tmux 3.7 or newer, where a development
     * or candidate build of 3.7 itself ranks below it ({@code next-3.7 < 3.7-rc < 3.7}) and
     * {@code 3.8} or {@code next-3.8} rank above.
     */
    public static boolean supports(TmuxVersion version) {
        return Capabilities.CONTROL_MODE.availableOn(version);
    }

    /**
     * Refuses a daemon whose control clients are not qualified, before any control client exists.
     *
     * @throws UnsupportedFeatureException below tmux 3.7
     */
    public static void requireSupported(TmuxVersion version) {
        if (!supports(version)) {
            throw new UnsupportedFeatureException(
                    "managed control acquisition requires tmux 3.7 or newer; daemon reports " + version);
        }
    }

    /**
     * Reads the daemon identity without firing a user's hooks.
     *
     * <p>As the version probe does, this uses {@code run-shell -C}: tmux parses the expansion as a
     * command and names the first word it cannot parse, so every field travels in one word. The
     * fields are joined with commas, and the socket path comes last inside single quotes with its own
     * quotes closed, escaped and reopened, so spaces and quotes in it survive the parse.
     */
    private static Identity read(ControlCarrier carrier, List<String> command, Duration timeout) {
        List<String> endpoint = new ArrayList<>(command);
        endpoint.add(1, "-N");
        String marker = "libtmux-identity-"
                + Long.toHexString(ThreadLocalRandom.current().nextLong()) + "-";
        CommandResult result;
        try {
            result = ProcessTransport.executeThroughCarrier(
                    carrier,
                    new CommandRequest(endpoint, List.of(List.of("run-shell", "-C", marker + FORMAT)), timeout, ""));
        } catch (DispatchException failure) {
            DispatchException denied = failure instanceof DispatchException.TimedOut
                    ? new DispatchException.TimedOut(
                            "control identity probe timed out", DispatchOutcome.NOT_DISPATCHED, failure)
                    : new DispatchException.Failed(
                            "control identity probe failed", DispatchOutcome.NOT_DISPATCHED, failure);
            throw denied;
        }
        String prefix = "unknown command: " + marker;
        List<String> answers =
                result.stderr().stream().filter(line -> line.startsWith(prefix)).toList();
        if (result.succeeded() || answers.size() != 1 || result.stderr().size() != 1) {
            throw new CommandRejectedException(
                    "cannot query the control endpoint" + ErrorText.suffix(result.stderr()),
                    "run-shell",
                    result.exitCode(),
                    result.stderr());
        }
        String[] fields = answers.getFirst().substring(prefix.length()).split(",", 4);
        try {
            if (fields.length != 4 || fields[3].isEmpty()) throw new NumberFormatException();
            long pid = Long.parseLong(fields[1]);
            long start = Long.parseLong(fields[2]);
            if (pid < 1 || start < 1) throw new NumberFormatException();
            return new Identity(fields[0], pid, start, fields[3]);
        } catch (NumberFormatException failure) {
            throw new MalformedResponseException("tmux reported an invalid control identity", failure);
        }
    }

    /** Rereads the identity through the pin and refuses a daemon that is no longer the qualified one. */
    public void recheck(ControlCarrier carrier) {
        if (!identity.equals(read(carrier, command, remaining(true)))) {
            throw new TargetGoneException("the tmux endpoint changed during control acquisition");
        }
    }

    public List<String> command() {
        return command;
    }

    public Identity identity() {
        return identity;
    }

    public void verify(long pid, OptionalLong start, String version) {
        if (identity.pid() != pid
                || !identity.version().equals(version)
                || (start.isPresent() && identity.start() != start.getAsLong())) {
            throw new TargetGoneException("the tmux server this handle belonged to has ended");
        }
    }

    public Duration remaining(boolean dispatched) {
        return remaining(budget, began, dispatched);
    }

    private static Duration remaining(Duration budget, long began, boolean dispatched) {
        Duration left = budget.minusNanos(System.nanoTime() - began);
        if (left.isZero() || left.isNegative()) {
            throw new DispatchException.TimedOut(
                    "control acquisition exceeded its deadline",
                    dispatched ? DispatchOutcome.UNKNOWN : DispatchOutcome.NOT_DISPATCHED,
                    null);
        }
        return left;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            pin.close();
        } catch (IOException failure) {
            throw new IllegalStateException("could not remove the owned control endpoint", failure);
        }
    }

    public record Identity(String version, long pid, long start, String socket) {}
}
