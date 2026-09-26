/**
 * Helpers shared across this module's packages, and not part of its API.
 *
 * <p>{@code module-info.java} does not export this package. Its types are public only because
 * several packages here share them, which is not a reason for a consumer to depend on them; nothing
 * in it carries a compatibility guarantee, alpha or otherwise.
 *
 * <p>The package is null-marked: every type is non-null unless annotated otherwise.
 */
@NullMarked
package io.github.libtmux.internal;

import org.jspecify.annotations.NullMarked;
