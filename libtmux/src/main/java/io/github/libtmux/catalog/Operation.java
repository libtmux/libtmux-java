package io.github.libtmux.catalog;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Classifies one public method of the core API as a tmux operation of a given {@link Kind}.
 *
 * <p>Every public method that performs or describes a tmux operation carries this, so a facade
 * never has to infer the shape of a call from its name or signature.
 */
@Documented
@Retention(RUNTIME)
@Target(METHOD)
public @interface Operation {

    /** What shape this operation gives a facade. */
    Kind value();

    /**
     * The first tmux release that has this operation, when it is newer than the supported floor,
     * 3.2a. Empty when the whole supported range has it.
     */
    String tmuxSince() default "";
}
