package com.git_pull.libtmux.transport;

import com.git_pull.libtmux.ExecutionMode;
import com.git_pull.libtmux.LibTmuxException;
import com.git_pull.libtmux.ServerConfig;
import com.git_pull.libtmux.SessionId;
import com.git_pull.libtmux.batch.OperationOutcome;
import com.git_pull.libtmux.control.ControlClient;
import com.git_pull.libtmux.control.ControlReply;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Carries every command over one persistent tmux client, in control mode.
 *
 * <p>The {@link ExecutionMode#CONTROL} carrier. One process serves the life of the server rather
 * than one per command.
 *
 * <p>Control mode attaches to a session, so there is nothing to attach to until a session exists.
 * Until then this delegates to a process transport, and attaches on the first command issued once
 * tmux has a session. The seam is invisible to a caller: both paths answer with a
 * {@link CommandResult}, and the only observable difference is how many processes were started.
 *
 * <p>Blocking happens on a {@link ReentrantLock} rather than in a {@code synchronized} block, so a
 * virtual thread waiting here releases its carrier.
 */
public final class ControlTransport implements TmuxTransport {

    private final ServerConfig config;
    private final TmuxTransport bootstrap;
    private final ReentrantLock attaching = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile @Nullable ControlClient client;

    /**
     * @param config which server to reach, and how
     * @param bootstrap carries commands until a session exists to attach to, and is closed with this
     */
    public ControlTransport(ServerConfig config, TmuxTransport bootstrap) {
        this.config = config;
        this.bootstrap = bootstrap;
    }

    @Override
    public CommandResult execute(CommandRequest request) {
        requireOpen();
        ControlClient attached = attachedClient();
        if (attached == null || carriedByProcess(request)) {
            return bootstrap.execute(request);
        }
        try {
            return asResult(attached.send(request.argv(), request.timeout()));
        } catch (LibTmuxException failed) {
            // A control client ends with the server it attached to, and a command sent afterwards
            // fails on the write — before tmux saw a complete line, so nothing was applied. A
            // carrier that has no client left is the state this transport already handles: drop it
            // and answer through a process, which is what happens before one is ever attached.
            //
            // Only when the client has actually gone. The same exception covers failures a live
            // client can raise, and re-running one of those over a process could apply it twice.
            if (attached.isAlive()) {
                throw failed;
            }
            discard(attached);
            return bootstrap.execute(request);
        }
    }

    /** Forgets a client that has ended, so the next command attaches again or falls back. */
    private void discard(ControlClient dead) {
        attaching.lock();
        try {
            if (client == dead) {
                client = null;
            }
        } finally {
            attaching.unlock();
        }
        dead.close();
    }

    /**
     * Commands tmux answers by running or waiting for something else, and their aliases.
     *
     * <p>Each one makes tmux do work of its own — run a guarded command, read a file of them, run a
     * hook's, block on a channel — and control mode reports that work in the same stream as the
     * replies. Those extra blocks belong to no request this client sent.
     */
    private static final Set<String> DEFERRING = Set.of(
            "if-shell",
            "if",
            "run-shell",
            "run",
            "source-file",
            "source",
            "wait-for",
            "wait",
            "confirm-before",
            "confirm");

    /**
     * Commands that end the client carrying them.
     *
     * <p>A control client is a client of the server it is talking to, so a command that ends the
     * server ends the connection the reply was going to arrive on. Sent down the client, it races
     * its own effect and fails to write as often as it succeeds.
     */
    private static final Set<String> SELF_DESTROYING = Set.of("kill-server");

    /**
     * Whether this request must go over a process rather than the control client.
     *
     * <p>Control mode frames a reply per command, not per line: a line holding two commands comes
     * back as two {@code %begin}/{@code %end} blocks. A client that expects one would take the first
     * and leave the second to be misread as the answer to whatever it sends next — corruption rather
     * than a failure, and silent. A capture is where it surfaces, as a window belonging to a session
     * the sessions listing never mentioned, because the empty reply to something else was read as
     * that listing.
     *
     * <p>So a request goes over a process whenever the stream cannot be trusted after it. Three
     * kinds cannot. A group says so in its argv, by tmux's own rule rather than by a semicolon
     * standing alone — see {@link ControlClient#isCommandGroup}. A command that makes tmux run or
     * await something else does not say so, and is recognised by name — including {@code set-hook
     * -R}, which runs a hook's commands rather than recording them. And a command that ends the
     * client carrying it can only race its own effect.
     *
     * <p>A group is one invocation either way, and the rest are rare enough that a process costs
     * nothing worth having. Nothing is lost but the illusion that control mode carried it.
     */
    private static boolean carriedByProcess(CommandRequest request) {
        List<String> argv = request.argv();
        if (argv.isEmpty()) {
            return false;
        }
        if (ControlClient.isCommandGroup(argv)) {
            return true;
        }
        String name = argv.get(0);
        return DEFERRING.contains(name)
                || SELF_DESTROYING.contains(name)
                || ("set-hook".equals(name) && argv.contains("-R"));
    }

    /**
     * The control client, attaching on first use, or null while there is no session to attach to.
     *
     * <p>Asked of the bootstrap transport rather than of a {@code Server}, because a transport is
     * what a server is built on and cannot ask one back.
     *
     * <p>Closing is checked on both sides of the attach rather than only at the door. Finding a
     * session takes a command of its own, so a close can land after this began and before a client
     * exists — and a close cannot release a client that was not there to be seen. Whoever attached
     * it is therefore the one that has to release it.
     */
    private @Nullable ControlClient attachedClient() {
        ControlClient existing = client;
        if (existing != null) {
            return existing;
        }
        attaching.lock();
        try {
            if (client != null) {
                return client;
            }
            requireOpen();
            SessionId session = firstSession();
            if (session == null) {
                return null;
            }
            requireOpen();
            ControlClient attached = ControlClient.attach(config, session);
            if (closed.get()) {
                attached.close();
                throw new IllegalStateException("transport is closed");
            }
            client = attached;
            return attached;
        } finally {
            attaching.unlock();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("transport is closed");
        }
    }

    private @Nullable SessionId firstSession() {
        CommandResult listed = bootstrap.execute(new CommandRequest(
                config.endpointCommand(), List.of("list-sessions", "-F", "#{session_id}"), config.defaultTimeout()));
        if (!listed.succeeded() || listed.stdout().isEmpty()) {
            return null;
        }
        return new SessionId(listed.stdout().get(0));
    }

    /**
     * Puts a control reply into the shape every carrier answers with.
     *
     * <p>Control mode has no exit status and no separate error channel, so one is synthesised: a
     * completed operation is zero, anything else is one, and its lines are reported as the error
     * channel. Nothing in the entity layer reads either — see {@code docs/spikes/19} — so this is
     * only visible to a caller who asked for process detail on purpose.
     */
    private static CommandResult asResult(ControlReply reply) {
        if (reply.outcome() == OperationOutcome.COMPLETE) {
            return new CommandResult(0, reply.lines(), List.of());
        }
        return new CommandResult(1, List.of(), reply.lines());
    }

    @Override
    public String realm() {
        return bootstrap.realm();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ControlClient attached = client;
        if (attached != null) {
            attached.close();
        }
        bootstrap.close();
    }
}
