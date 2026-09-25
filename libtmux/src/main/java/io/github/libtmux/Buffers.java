package io.github.libtmux;

import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.exception.ServerUnavailableException;
import io.github.libtmux.exception.TargetGoneException;
import io.github.libtmux.exception.UnsupportedFeatureException;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.transport.CommandResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kotlin.annotations.jvm.ReadOnly;

/**
 * The tmux server's paste buffers.
 *
 * <p>Buffers are the server's, not a session's: anything copied in one session can be pasted into
 * another. They are addressed by name rather than by the stack position tmux also accepts, because
 * a position moves whenever anything else is copied.
 */
public final class Buffers {

    private static final RowFormat LISTING = RowFormat.of("buffer_name", "buffer_size");

    /**
     * tmux's own history dates the fix to {@code 0f6227f4} ("When deleting or renaming a buffer and
     * a buffer name is specified, complain if the buffer doesn't exist instead of silently deleting
     * or renaming the most recent buffer", GitHub issue 3205); {@code git tag --contains} places it
     * on {@code 3.4} and nothing earlier, so 3.3 and 3.3a both have the bug. Confirmed on the matrix:
     * {@code set-buffer -b a x; set-buffer -b b y; delete-buffer -b nope} answers {@code unknown
     * buffer: nope} on 3.4 and leaves both buffers, but on 3.3a it exits 0 and takes {@code b}, the
     * one {@code delete-buffer} was never told to touch.
     */
    static final TmuxVersion EXACT_NAMED_DELETE = new TmuxVersion(3, 4, "");

    private final Server server;

    Buffers(Server server) {
        this.server = server;
    }

    /**
     * Captures every buffer the server holds, in tmux's order.
     *
     * @return an immutable list, empty if the live server holds no buffers
     * @throws LibTmuxException if the listing fails, including when no daemon is running
     */
    @ReadOnly
    public List<BufferInfo> list() {
        List<BufferInfo> buffers = new ArrayList<>();
        var result = server.run(List.of("list-buffers", "-F", LISTING.template()));
        for (String row : result.stdout()) {
            List<String> fields = LISTING.split(row);
            buffers.add(new BufferInfo(fields.get(0), Integer.parseInt(fields.get(1))));
        }
        return List.copyOf(buffers);
    }

    /**
     * Puts text in a named buffer, replacing whatever was there.
     *
     * <p>The contents end the options, because they are the one argument here tmux would otherwise
     * read as flags: {@code set("clip", "-nfoo")} used to rename the buffer to {@code foo} and write
     * nothing, and report success.
     */
    public void set(String name, String contents) {
        server.run(List.of("set-buffer", "-b", name, "--", contents));
    }

    /**
     * What a buffer holds, without any line breaks it ends with.
     *
     * <p>tmux prints a buffer as it is, and the transport drops trailing blank lines, so {@code "a"}
     * and {@code "a\n"} read the same. A marker printed after it cannot keep them: tmux writes a
     * buffer through its file stream, which a second command in the same invocation interrupts.
     * {@link #save} writes the exact bytes.
     *
     * @throws TargetGoneException if the server has no buffer by that name
     * @throws ServerUnavailableException if no daemon is running
     */
    public String show(String name) {
        CommandResult result = server.cmd(List.of("show-buffer", "-b", name));
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.equals("no buffer " + name))) {
            throw new TargetGoneException("no buffer named '" + name + "'");
        }
        if (!result.succeeded()) {
            throw server.failed("show-buffer", result);
        }
        return String.join("\n", result.stdout());
    }

    /**
     * Removes a buffer by its exact name.
     *
     * @throws TargetGoneException if the server has no buffer by that name
     * @throws ServerUnavailableException if no daemon is running
     * @throws UnsupportedFeatureException before tmux 3.4, whose named deletion silently removes the top
     *     buffer when the name is absent
     */
    public void delete(String name) {
        TmuxVersion running = server.version();
        if (!running.atLeast(EXACT_NAMED_DELETE)) {
            throw new UnsupportedFeatureException("deleting a buffer by exact name", EXACT_NAMED_DELETE, running);
        }
        CommandResult result = server.cmd(List.of("delete-buffer", "-b", name));
        if (!result.succeeded() && result.stderr().stream().anyMatch(line -> line.equals("unknown buffer: " + name))) {
            throw new TargetGoneException("no buffer named '" + name + "'");
        }
        if (!result.succeeded()) {
            throw server.failed("delete-buffer", result);
        }
    }

    /** Writes a buffer's contents to a file. */
    public void save(String name, Path file) {
        server.run(List.of("save-buffer", "-b", name, "--", file.toString()));
    }

    /** Reads a file into a named buffer. */
    public void load(String name, Path file) {
        server.run(List.of("load-buffer", "-b", name, "--", file.toString()));
    }
}
