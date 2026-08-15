package io.github.libtmux;

import io.github.libtmux.format.RowFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The tmux server's paste buffers.
 *
 * <p>Buffers are the server's, not a session's: anything copied in one session can be pasted into
 * another. They are addressed by name rather than by the stack position tmux also accepts, because
 * a position moves whenever anything else is copied.
 */
public final class Buffers {

    private static final RowFormat LISTING = RowFormat.of("buffer_name", "buffer_size");

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
        server.run(List.of("set-buffer", "-b", name, contents));
    }

    /**
     * What a buffer holds.
     *
     * @throws ObjectDoesNotExist if the server has no buffer by that name
     */
    public String show(String name) {
        var result = server.cmd(List.of("show-buffer", "-b", name));
        if (!result.succeeded()) {
            throw new ObjectDoesNotExist("no buffer named '" + name + "'");
        }
        return String.join("\n", result.stdout());
    }

    /** Removes a buffer. */
    public void delete(String name) {
        server.run(List.of("delete-buffer", "-b", name));
    }

    /** Writes a buffer's contents to a file. */
    public void save(String name, Path file) {
        server.run(List.of("save-buffer", "-b", name, file.toString()));
    }

    /** Reads a file into a named buffer. */
    public void load(String name, Path file) {
        server.run(List.of("load-buffer", "-b", name, file.toString()));
    }
}
