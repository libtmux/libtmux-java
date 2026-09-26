package io.github.libtmux.catalog;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Public for orchestration across processes or clients, such as the MCP server's pane
 * reservations; not part of the surface a language facade mirrors.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface Advanced {}
