package com.git_pull.libtmux.junit5;

import java.nio.file.Path;

/**
 * The socket a fixture server is listening on.
 *
 * <p>A distinct type rather than a bare {@link Path} so the extension can resolve it without
 * claiming every {@code Path} parameter a test might want from some other extension.
 *
 * @param path the exact socket path this test's tmux was started with
 */
public record TmuxSocketPath(Path path) {}
