package io.github.libtmux;

import io.github.libtmux.internal.CommandStrings;
import io.github.libtmux.snapshot.ServerSnapshot;
import io.github.libtmux.snapshot.WindowContext;
import io.github.libtmux.transport.CommandResult;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Which tmux server incarnation a handle or batch may act on: the pid tmux reports, and its start
 * time when known, since a pid alone is reusable — a tmux started on the pid of one just probed, as
 * a container's first processes often are, would otherwise answer as if it were that server.
 *
 * <p>Every guarded command is sent as tmux's own {@code if-shell -F} test of this condition, so a
 * server that has been replaced under a stale handle is refused before anything runs, rather than
 * acting on the wrong incarnation.
 */
final class IncarnationFence {

    private final Server server;
    private final ServerIdentity identity;

    IncarnationFence(Server server, ServerIdentity identity) {
        this.server = server;
        this.identity = identity;
    }

    ServerIdentity identity() {
        return identity;
    }

    ServerIdentity identity(ServerSnapshot snapshot) {
        return snapshot.serverPid().isPresent()
                ? identity.at(snapshot.serverPid().orElseThrow(), snapshot.serverStartTime())
                : identity;
    }

    void requireSameServer(Server other) {
        Objects.requireNonNull(other, "other");
        if (!identity.equals(other.identity())) {
            throw new IllegalArgumentException("handles belong to different tmux servers");
        }
    }

    void requireSameIncarnation(ServerSnapshot snapshot, Server other, ServerSnapshot otherSnapshot) {
        requireSameServer(other);
        if (!identity(snapshot).equals(other.identity(otherSnapshot))) {
            throw new IllegalArgumentException("handles belong to different tmux server incarnations");
        }
    }

    /** A condition true only on the server a capture named: its pid, and its start time when known. */
    static String incarnation(long pid, OptionalLong startTime) {
        String samePid = "#{==:#{pid}," + pid + "}";
        return startTime.isPresent()
                ? "#{&&:" + samePid + ",#{==:#{start_time}," + startTime.getAsLong() + "}}"
                : samePid;
    }

    /** Refuses to reach a tmux server that is not the one this handle was made against. */
    CommandResult guarded(ServerSnapshot snapshot, String command) {
        return guarded(snapshot, command, "");
    }

    CommandResult guarded(ServerSnapshot snapshot, String command, String input) {
        long pid = snapshot.serverPid()
                .orElseThrow(() -> new IllegalStateException("a live handle has no server process identity"));
        return guarded(pid, incarnation(pid, snapshot.serverStartTime()), command, input);
    }

    CommandResult guarded(long pid, String condition, String command, String input) {
        String stale = "libtmux-stale-handle-" + pid;
        CommandResult result = server.cmd(
                List.of("if-shell", "-F", condition, command, stale),
                server.config().defaultTimeout(),
                input);
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.contains(stale))) {
            throw new ObjectDoesNotExistException("the tmux server this handle belonged to has ended");
        }
        return result;
    }

    /** As {@link #guarded(ServerSnapshot, String, String)}, refused as well once the window has gone. */
    CommandResult run(ServerSnapshot snapshot, WindowContext expected, List<String> argv) {
        long pid = snapshot.serverPid()
                .orElseThrow(() -> new IllegalStateException("a live handle has no server process identity"));
        String target = expected.session().value() + ":" + expected.index().value();
        String stale = "libtmux-stale-winlink-" + pid + "-" + expected.window().value();
        String condition = "#{&&:" + incarnation(pid, snapshot.serverStartTime()) + ",#{==:#{window_id},"
                + expected.window().value() + "}}";
        CommandResult result =
                server.cmd(List.of("if-shell", "-F", "-t", target, condition, CommandStrings.stringify(argv), stale));
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.contains(stale))) {
            throw new ObjectDoesNotExistException("window " + expected.window() + " no longer exists here");
        }
        if (!result.succeeded()) {
            throw server.failed(argv.get(0), result);
        }
        return result;
    }
}
