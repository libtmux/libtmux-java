/**
 * Exposing a tmux server to a model through the Model Context Protocol.
 *
 * <p>The tool layer is deliberately separate from the protocol layer: what a tool does to tmux is
 * worth testing against real tmux, and wiring it to a transport is not.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.mcp;

import org.jspecify.annotations.NullMarked;
