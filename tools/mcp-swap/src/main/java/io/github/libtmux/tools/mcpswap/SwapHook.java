package io.github.libtmux.tools.mcpswap;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface SwapHook {
    SwapHook NONE = (boundary, path) -> {};

    void before(String boundary, Path path) throws IOException;
}
