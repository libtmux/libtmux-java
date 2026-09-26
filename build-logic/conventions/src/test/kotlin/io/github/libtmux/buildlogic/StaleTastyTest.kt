package io.github.libtmux.buildlogic

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StaleTastyTest {

    @Test
    fun `deletes only the TASTy no class file sits beside`(@TempDir root: Path) {
        val classes = root.toFile()
        val kept = listOf("a/Kept.tasty", "a/Kept.class", "a/Kept$.class", "b/Top\$package.tasty", "b/Top\$package.class")
        val stale = listOf("a/Removed.tasty", "b/Gone\$package.tasty")
        (kept + stale).forEach { File(classes, it).apply { parentFile.mkdirs() }.writeText("") }

        val pruned = pruneStaleTasty(classes).map { it.relativeTo(classes).invariantSeparatorsPath }.sorted()

        assertEquals(stale.sorted(), pruned)
        assertEquals(kept.sorted(), classes.walk().filter { it.isFile }.map { it.relativeTo(classes).invariantSeparatorsPath }.sorted().toList())
    }
}
