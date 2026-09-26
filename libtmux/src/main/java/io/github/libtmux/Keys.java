package io.github.libtmux;

import io.github.libtmux.catalog.Kind;
import io.github.libtmux.catalog.Operation;
import io.github.libtmux.exception.ServerUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kotlin.annotations.jvm.ReadOnly;
import org.jspecify.annotations.Nullable;

/**
 * The server's key bindings, in one key table or across all of them.
 *
 * <p>tmux keeps bindings in named tables: {@code prefix}, pressed after the prefix key, is where a
 * binding goes unless another table is named; {@code root} is pressed without it; the copy-mode
 * tables belong to copy mode. {@link Server#keys()} answers for {@code prefix} when binding and for
 * every table when listing; {@link #in} names one.
 *
 * <pre>{@code
 * server.keys().bind("F12", List.of("display-message", "hello"));
 * server.keys().in("root").bind("F11", List.of("next-window"));
 * }</pre>
 */
public final class Keys {

    private final Server server;
    private final @Nullable String table;

    Keys(Server server, @Nullable String table) {
        this.server = server;
        this.table = table;
    }

    /** The same bindings, in one named table. */
    @Operation(Kind.CAPTURED)
    public Keys in(String table) {
        Objects.requireNonNull(table, "table");
        if (table.isEmpty()) {
            throw new IllegalArgumentException("a key table has a name");
        }
        return new Keys(server, table);
    }

    /**
     * Binds a key to a tmux command, given as its words.
     *
     * <p>Each word reaches tmux as itself: the command is passed as arguments, never as a line for
     * tmux to split.
     */
    @Operation(Kind.MUTATION)
    public void bind(String key, List<String> command) {
        Objects.requireNonNull(key, "key");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("a key binding has no command");
        }
        List<String> argv = new ArrayList<>(List.of("bind-key"));
        argv.addAll(scope());
        argv.add("--");
        argv.add(key);
        argv.addAll(command);
        server.run(argv);
    }

    /** Removes a key's binding. */
    @Operation(Kind.MUTATION)
    public void unbind(String key) {
        Objects.requireNonNull(key, "key");
        List<String> argv = new ArrayList<>(List.of("unbind-key"));
        argv.addAll(scope());
        argv.add("--");
        argv.add(key);
        server.run(argv);
    }

    /**
     * The bindings as tmux lists them, one per line.
     *
     * <p>Every table unless one was named. Read without starting a daemon to answer, which tmux
     * would otherwise do for {@code list-keys}: its tables are compiled in, so it can answer an
     * endpoint nothing serves by serving it.
     *
     * @throws ServerUnavailableException if no daemon is running
     */
    @ReadOnly
    @Operation(Kind.READ)
    public List<String> list() {
        List<String> argv = new ArrayList<>(List.of("list-keys"));
        argv.addAll(scope());
        return server.withoutStartingServer(argv).stdout();
    }

    private List<String> scope() {
        return table == null ? List.of() : List.of("-T", table);
    }
}
