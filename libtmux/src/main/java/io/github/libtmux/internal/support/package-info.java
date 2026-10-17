/**
 * The tmux version check: whether the daemon behind an endpoint runs a supported tmux release.
 *
 * <p>"Support" here means the tmux releases this library works with, not helper code. Like its
 * parent package, {@code module-info.java} does not export it, and nothing in it carries a
 * compatibility guarantee.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.internal.support;

import org.jspecify.annotations.NullMarked;
