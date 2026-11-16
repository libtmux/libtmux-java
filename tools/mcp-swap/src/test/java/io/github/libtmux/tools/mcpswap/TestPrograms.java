package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The Python programs the tests run, kept as checked files under the test resources. */
final class TestPrograms {
    private TestPrograms() {}

    /** Copies a program from the test resources into a directory and returns the copy. */
    static Path copy(String name, Path directory) throws IOException {
        var target = directory.resolve(name);
        try (var source = TestPrograms.class.getResourceAsStream("/io/github/libtmux/tools/mcpswap/" + name)) {
            if (source == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
