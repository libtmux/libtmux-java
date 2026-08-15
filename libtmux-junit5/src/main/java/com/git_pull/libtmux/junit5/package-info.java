/**
 * Real tmux fixtures for JUnit 5.
 *
 * <p>Every test gets its own server on its own socket, and teardown is guaranteed whatever the test
 * did. Nothing here mutates a process-global home or environment value.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package com.git_pull.libtmux.junit5;

import org.jspecify.annotations.NullMarked;
