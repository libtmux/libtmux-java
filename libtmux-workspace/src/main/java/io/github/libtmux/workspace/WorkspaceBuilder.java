package io.github.libtmux.workspace;

import io.github.libtmux.Server;
import io.github.libtmux.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a tmux session from a written description.
 *
 * <p>Reads the shape of a tmuxp workspace file. Full runtime compatibility with tmuxp is not the
 * aim; starting from a file somebody already has is.
 *
 * <p>Reading and building are separate. A file that describes something tmux would refuse is
 * rejected while it is still text, before any session exists to leave half-built.
 */
public final class WorkspaceBuilder {

    private WorkspaceBuilder() {}

    /** Reads a workspace from a file. */
    public static Workspace read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the workspace file", e);
        }
    }

    /**
     * Reads a workspace from YAML text.
     *
     * @throws IllegalArgumentException if the description is one tmux could not build, including a
     *     layout name tmux would not recognise
     */
    public static Workspace parse(String yaml) {
        return WorkspaceParser.parse(yaml);
    }

    /**
     * Creates the described session on a server.
     *
     * @return the session, from a capture taken once everything exists
     */
    public static Session build(Server server, Workspace workspace) {
        return WorkspaceApplier.build(server, workspace);
    }
}
