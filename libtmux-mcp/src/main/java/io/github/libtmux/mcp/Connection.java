package io.github.libtmux.mcp;

import io.github.libtmux.Server;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** What every tool, resource and prompt on this connection shares. */
final class Connection {

    private final Server server;
    private final Caller caller;
    private final Safety ceiling;
    private final Set<String> ownClients;
    private final ReentrantReadWriteLock clientVisibility = new ReentrantReadWriteLock(true);

    Connection(Server server, Caller caller, Safety ceiling, Set<String> ownClients) {
        this.server = server;
        this.caller = caller;
        this.ceiling = ceiling;
        this.ownClients = ownClients;
    }

    static Connection to(Server server, Safety ceiling) {
        return new Connection(server, Caller.of(server), ceiling, ConcurrentHashMap.newKeySet());
    }

    Server server() {
        return server;
    }

    Caller caller() {
        return caller;
    }

    Safety ceiling() {
        return ceiling;
    }

    /** Runs a watcher-client transition without exposing its half-finished state to a listing. */
    <T> T changeClients(Supplier<T> change) {
        return locked(clientVisibility.writeLock(), change);
    }

    void changeClients(Runnable change) {
        changeClients(() -> {
            change.run();
            return null;
        });
    }

    /** Reads tmux clients and the hidden-client set as one stable view. */
    <T> T withStableClients(Supplier<T> read) {
        return locked(clientVisibility.readLock(), read);
    }

    private static <T> T locked(Lock lock, Supplier<T> operation) {
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops a client this server attached for its own purposes being reported as a person.
     *
     * <p>Watching a server means attaching a control client to it, and an attached client is exactly
     * what {@code tmux_list_clients} answers "is anybody looking at this" with. Left in, this
     * server's own watcher would make every session look occupied.
     */
    void hide(String clientName) {
        ownClients.add(clientName);
    }

    /** Stops hiding a watcher client after that attachment ends. */
    void reveal(String clientName) {
        ownClients.remove(clientName);
    }

    boolean isOurs(String clientName) {
        return ownClients.contains(clientName);
    }

    /** One invocation on this connection. */
    Call call(java.util.Map<String, Object> arguments, Call.Progress progress) {
        return new Call(this, arguments, progress);
    }
}
