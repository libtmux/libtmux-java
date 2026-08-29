package io.github.libtmux;

import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tmux server's paste buffers.
 *
 * <p>Buffers are the server's, not a session's: anything copied in one session can be pasted into
 * another. They are addressed by name rather than by the stack position tmux also accepts, because
 * a position moves whenever anything else is copied.
 */
public final class Buffers {

    private static final RowFormat LISTING = RowFormat.of("buffer_name", "buffer_size");
    static final TmuxVersion EXACT_NAMED_DELETE = new TmuxVersion(3, 4, "");

    private final Server server;

    Buffers(Server server) {
        this.server = server;
    }

    /** Every buffer the server holds, in tmux's order. */
    public List<BufferInfo> list() {
        List<BufferInfo> buffers = new ArrayList<>();
        var result = server.cmd(List.of("list-buffers", "-F", LISTING.template()));
        if (!result.succeeded()) {
            // An empty stack is not a failure worth raising for, and tmux says so with an error.
            return List.of();
        }
        for (String row : result.stdout()) {
            List<String> fields = LISTING.split(row);
            buffers.add(new BufferInfo(fields.get(0), Integer.parseInt(fields.get(1))));
        }
        return List.copyOf(buffers);
    }

    /** Puts text in a named buffer, replacing whatever was there. */
    public void set(String name, String contents) {
        server.run(List.of("set-buffer", "-b", argument(name), argument(contents)));
    }

    /**
     * What a buffer holds.
     *
     * @throws ObjectDoesNotExist if the server has no buffer by that name
     */
    public String show(String name) {
        var result = server.cmd(List.of("show-buffer", "-b", argument(name)));
        if (!result.succeeded()) {
            throw new ObjectDoesNotExist("no buffer named '" + name + "'");
        }
        return String.join("\n", result.stdout());
    }

    /**
     * Removes a buffer by its exact name.
     *
     * @throws ObjectDoesNotExist if the server has no buffer by that name
     * @throws UnsupportedTmuxVersion before tmux 3.4, whose named deletion silently removes the top
     *     buffer when the name is absent
     */
    public void delete(String name) {
        String target = argument(name);
        TmuxVersion running = server.version();
        if (!running.atLeast(EXACT_NAMED_DELETE)) {
            throw new UnsupportedTmuxVersion("deleting a buffer by exact name", EXACT_NAMED_DELETE, running);
        }
        CommandResult result = server.cmd(List.of("delete-buffer", "-b", target));
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.equals("unknown buffer: " + name))) {
            throw new ObjectDoesNotExist("no buffer named '" + name + "'");
        }
        if (!result.succeeded()) {
            throw new LibTmuxException("tmux delete-buffer failed: " + String.join("; ", result.stderr()));
        }
    }

    /** Writes a buffer's contents to a file. */
    public void save(String name, Path file) {
        server.run(List.of("save-buffer", "-b", argument(name), argument(file.toString())));
    }

    /** Reads a file into a named buffer. */
    public void load(String name, Path file) {
        server.run(List.of("load-buffer", "-b", argument(name), argument(file.toString())));
    }

    /** Protects a final semicolon from tmux's command-group parser on every transport. */
    private static String argument(String value) {
        Objects.requireNonNull(value, "value");
        return value.endsWith(";") ? value.substring(0, value.length() - 1) + "\\;" : value;
    }
}
