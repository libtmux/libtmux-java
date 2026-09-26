package io.github.libtmux.buildlogic

import java.io.File

/**
 * Deletes the TASTy files under [classes] that no class file sits beside, and returns them.
 *
 * Zinc deletes a removed source's class files and leaves its TASTy, because Gradle does not register
 * `.tasty` as an auxiliary class file the way sbt does (https://github.com/gradle/gradle/issues/39310).
 * The compiler writes every `.tasty` beside a `.class` of the same name, so one without is left from a
 * source that is gone. Left in place, scaladoc documents the class, a macro walking the package finds
 * it, and the build cache stores it with the rest of the output.
 */
fun pruneStaleTasty(classes: File): List<File> =
    classes.walk()
        .filter { it.isFile && it.extension == "tasty" }
        .filterNot { it.resolveSibling("${it.nameWithoutExtension}.class").exists() }
        .toList()
        .onEach { check(it.delete()) { "could not delete $it" } }
