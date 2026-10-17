package io.github.libtmux.internal.support;

import io.github.libtmux.TmuxVersion;
import io.github.libtmux.exception.CommandRejectedException;
import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.MalformedResponseException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.internal.ErrorText;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ControlCarrier;
import io.github.libtmux.transport.DispatchOutcome;
import io.github.libtmux.transport.Idempotence;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** The tmux version check shared by command dispatch and control-client startup. */
public final class Versions {

    private static final Pattern VERSION = Pattern.compile("(?:next-\\d+\\.\\d+|\\d+\\.\\d+[a-z]?(?:-rc\\d*)?)");
    private static final TmuxVersion MINIMUM = new TmuxVersion(3, 3, "a");
    private static final String PROBE_MARKER = "libtmux-version-";
    private static final String UNKNOWN_COMMAND = "unknown command: ";
    private static final String CHANGED = "libtmux-stale-version";

    private Versions() {}

    /** Checks the live daemon before dispatch and fences the requested commands on that version. */
    public static CommandResult execute(TmuxTransport transport, CommandRequest request, boolean waiting) {
        long started = System.nanoTime();
        if (request.commands().equals(List.of(List.of("-V")))
                || request.commands().equals(List.of(List.of("-h")))) {
            return transport.execute(request);
        }
        Probe probe = probe(transport, request);
        String version;
        if (probe.version().isPresent()) {
            version = probe.version().get();
            require(version);
        } else if (absent(probe.result())) {
            if (noStart(request.endpoint()) || request.idempotence() == Idempotence.IDEMPOTENT || cleanup(request)) {
                CommandRequest absent = new CommandRequest(
                        withoutStarting(request.endpoint()),
                        request.commands(),
                        remaining(request, started),
                        request.input());
                return dispatch(transport, absent, prefix(request.commands(), "0"), absent.timeout(), waiting);
            }
            version = binaryVersion(transport, request, remaining(request, started));
        } else {
            throw rejected(request, probe.result());
        }
        CommandRequest checked = probe.version().isPresent()
                ? new CommandRequest(
                        withoutStarting(request.endpoint()), request.commands(), request.timeout(), request.input())
                : request;
        return dispatch(
                transport, checked, guarded(request.commands(), version), remaining(request, started), waiting);
    }

    /** Reports the daemon version, or validates the executable when the endpoint is unused. */
    public static TmuxVersion versionForCreation(TmuxTransport transport, CommandRequest request) {
        long started = System.nanoTime();
        Probe daemon = probe(transport, request);
        if (daemon.version().isPresent()) return require(daemon.version().get());
        if (!absent(daemon.result())) throw rejected(request, daemon.result());
        return TmuxVersion.parse(binaryVersion(transport, request, remaining(request, started)));
    }

    /** What the version probe learned: the daemon's version, or the failure that says why not. */
    private record Probe(Optional<String> version, CommandResult result) {}

    /**
     * Asks the daemon for its version without running a command that fires a user's hooks.
     *
     * <p>{@code run-shell -C} parses its expanded argument as a command, so the format expands in
     * the daemon and the parse failure carries the answer on stderr as
     * {@code unknown command: <marker><version>}. Unlike {@code display-message} the command runs
     * no {@code after-*} hook, and the parse failure never reaches {@code command-error}.
     */
    private static Probe probe(TmuxTransport transport, CommandRequest request) {
        String marker = marker();
        CommandResult result;
        try {
            result = transport.execute(new CommandRequest(
                    withoutStarting(request.endpoint()), List.of(probeCommand(marker)), request.timeout(), ""));
        } catch (DispatchException failure) {
            throw beforeDispatch(failure, request);
        }
        return new Probe(answer(result, marker, request.endpoint().getFirst()), result);
    }

    private static String marker() {
        return PROBE_MARKER + Long.toHexString(ThreadLocalRandom.current().nextLong()) + "-";
    }

    private static List<String> probeCommand(String marker) {
        return List.of("run-shell", "-C", marker + "#{version}");
    }

    /** The version a probe's refusal spelled, or empty when tmux answered anything else. */
    private static Optional<String> answer(CommandResult result, String marker, String binary) {
        if (result.succeeded()) {
            throw new MalformedResponseException(binary + " exited 0 and reported no tmux version; is it tmux?");
        }
        String expected = UNKNOWN_COMMAND + marker;
        for (String line : result.stderr()) {
            if (line.startsWith(expected) && line.length() > expected.length()) {
                return Optional.of(line.substring(expected.length()));
            }
        }
        return Optional.empty();
    }

    /** Checks through the carrier without taking ownership of the caller's carrier. */
    public static void requireCarrier(ControlCarrier carrier, List<String> endpoint, Duration timeout) {
        String marker = marker();
        CommandRequest request =
                new CommandRequest(withoutStarting(endpoint), List.of(probeCommand(marker)), timeout, "");
        CommandResult result;
        try {
            result = ProcessTransport.executeThroughCarrier(carrier, request);
        } catch (DispatchException failure) {
            throw beforeDispatch(failure, request);
        }
        Optional<String> version = answer(result, marker, endpoint.getFirst());
        if (version.isEmpty()) throw rejected(request, result);
        require(version.get());
    }

    private static boolean cleanup(CommandRequest request) {
        return request.commands().stream()
                        .anyMatch(command -> command.getFirst().equals("kill-server"))
                && request.commands().stream()
                        .allMatch(command -> command.getFirst().equals("kill-server")
                                || command.equals(List.of("display-message", "-p", "#{pid}")));
    }

    /** The minimum applies to the daemon's own report, including patch letters. */
    public static TmuxVersion require(String reported) {
        if (!VERSION.matcher(reported).matches()) {
            throw new MalformedResponseException("tmux reported an unrecognized version: " + reported);
        }
        TmuxVersion running;
        try {
            running = TmuxVersion.parse(reported);
        } catch (IllegalArgumentException malformed) {
            throw new MalformedResponseException("tmux reported an unrecognized version: " + reported, malformed);
        }
        if (running.compareTo(MINIMUM) < 0) {
            throw new UnsupportedFeatureException("libtmux core", MINIMUM, running);
        }
        return running;
    }

    private static String binaryVersion(TmuxTransport transport, CommandRequest request, Duration timeout) {
        CommandResult result;
        try {
            result = transport.execute(new CommandRequest(request.endpoint(), List.of(List.of("-V")), timeout, ""));
        } catch (DispatchException failure) {
            throw beforeDispatch(failure, request);
        }
        if (!result.succeeded()) {
            throw rejected(request, result);
        }
        String line = reported(result, request.endpoint().getFirst());
        // tmux 3.3a reports argv[0]'s basename; later releases print tmux.
        String prefix = new File(request.endpoint().getFirst()).getName() + " ";
        if (!line.startsWith(prefix)) prefix = "tmux ";
        if (!line.startsWith(prefix)) {
            throw new MalformedResponseException(
                    request.endpoint().getFirst() + " did not report a tmux version: " + line);
        }
        String version = line.substring(prefix.length());
        try {
            require(version);
        } catch (UnsupportedFeatureException unsupported) {
            throw new UnsupportedFeatureException(
                    "libtmux core requires tmux " + MINIMUM + ", but the configured binary reports " + version);
        }
        return version;
    }

    private static CommandResult dispatch(
            TmuxTransport transport,
            CommandRequest original,
            List<List<String>> commands,
            Duration timeout,
            boolean waiting) {
        CommandRequest guarded = new CommandRequest(original.endpoint(), commands, timeout, original.input());
        CommandResult result = waiting ? transport.executeWaiting(guarded) : transport.execute(guarded);
        if (!result.succeeded()
                && result.stderr().stream()
                        .anyMatch(line -> line.contains(commands.getFirst().getLast()))) {
            throw new TargetGoneException("the tmux daemon version changed before dispatch");
        }
        return result;
    }

    private static List<List<String>> guarded(List<List<String>> commands, String version) {
        return prefix(commands, "#{==:#{version}," + version + "}");
    }

    private static List<List<String>> prefix(List<List<String>> commands, String condition) {
        List<List<String>> fenced = new ArrayList<>(commands.size() + 1);
        // A space is a tmux no-op and keeps JDK process-argument inspection intact.
        fenced.add(List.of("if-shell", "-F", condition, " ", CHANGED + "-" + UUID.randomUUID()));
        fenced.addAll(commands);
        return List.copyOf(fenced);
    }

    private static List<String> withoutStarting(List<String> endpoint) {
        // A selector or filename may itself equal -N, so always insert a flag.
        List<String> selected = new ArrayList<>(endpoint);
        selected.add(1, "-N");
        return List.copyOf(selected);
    }

    /** Recognizes global -N without treating option values as flags. */
    private static boolean noStart(List<String> endpoint) {
        for (int index = 1; index < endpoint.size(); index++) {
            String option = endpoint.get(index);
            if (!option.startsWith("-") || option.equals("--")) return false;
            for (int letter = 1; letter < option.length(); letter++) {
                char flag = option.charAt(letter);
                if (flag == 'N') return true;
                if ("cfLST".indexOf(flag) >= 0) {
                    if (letter + 1 == option.length()) index++;
                    break;
                }
            }
        }
        return false;
    }

    /** Whether tmux explicitly reported that nothing is serving the selected endpoint. */
    public static boolean absent(CommandResult result) {
        return !result.succeeded()
                && result.stderr().stream()
                        .anyMatch(line ->
                                line.contains("no server running") || line.contains("(No such file or directory)"));
    }

    private static String reported(CommandResult result, String binary) {
        if (result.stdout().size() != 1 || result.stdout().getFirst().isBlank()) {
            throw new MalformedResponseException(binary + " exited 0 and reported no single tmux version; is it tmux?");
        }
        return result.stdout().getFirst();
    }

    private static LibTmuxException rejected(CommandRequest request, CommandResult result) {
        String error = ErrorText.suffix(result.stderr());
        String message = "tmux version probe failed (exit " + result.exitCode() + ")"
                + (error.isEmpty() ? "; check that " + request.endpoint().getFirst() + " is tmux" : error);
        return absent(result)
                ? new ServerUnavailableException(message, "run-shell", result.exitCode(), result.stderr())
                : new CommandRejectedException(message, "run-shell", result.exitCode(), result.stderr());
    }

    private static Duration remaining(CommandRequest request, long started) {
        Duration left = request.timeout().minusNanos(System.nanoTime() - started);
        if (left.isZero() || left.isNegative()) {
            throw new DispatchException.TimedOut(
                    "the tmux version check exceeded the request deadline",
                    DispatchOutcome.NOT_DISPATCHED,
                    request.idempotence(),
                    null);
        }
        return left;
    }

    private static DispatchException beforeDispatch(DispatchException failure, CommandRequest request) {
        DispatchException rejected = failure instanceof DispatchException.TimedOut
                ? new DispatchException.TimedOut(
                        String.valueOf(failure.getMessage()),
                        DispatchOutcome.NOT_DISPATCHED,
                        request.idempotence(),
                        failure.getCause())
                : new DispatchException.Failed(
                        String.valueOf(failure.getMessage()),
                        DispatchOutcome.NOT_DISPATCHED,
                        request.idempotence(),
                        failure.getCause());
        rejected.addSuppressed(failure);
        return rejected;
    }
}
