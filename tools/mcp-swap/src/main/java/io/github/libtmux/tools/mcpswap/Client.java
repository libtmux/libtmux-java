package io.github.libtmux.tools.mcpswap;

import java.nio.file.Path;

record Client(String name, Path configPath, String serverTable, ConfigFormat format, boolean openCode) {}
