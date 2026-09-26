package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.Server;
import io.github.libtmux.ServerConfig;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.TmuxTransport;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The server instructions against the budget a live client silently truncates past.
 *
 * <p>Claude Code caps a server's {@code instructions} at 2048 bytes and drops what does not fit,
 * with nothing telling either side. Past that point, the one place a rule spanning several tools
 * can be stated stops reaching the model it was written for. Every toolset combination is checked,
 * since the transmitted string grows with the enabled set, using a socket path at this project's
 * own ~104-byte unix-socket ceiling — the longest one a real deployment can have.
 */
final class InstructionsTest {

    private static final int CLAUDE_CODE_INSTRUCTIONS_MAX_BYTES = 2048;
    private static final String[] TOOLSET_NAMES = {"inspect", "manage", "execute", "teardown"};

    @Test
    void everyToolsetCombinationFitsClaudeCodesInstructionsBudget() {
        String prefix = "/tmp/libtmux-java-test/";
        String stressPath = prefix + "s".repeat(104 - prefix.length());
        SocketProfile stress = new SocketProfile(
                "path:" + stressPath,
                "operator-current",
                "existing",
                "unknown",
                stressPath,
                "tmux -N -S '" + stressPath + "' attach",
                false);

        try (Server server = Server.using(ServerConfig.builder().build(), noOpTransport())) {
            for (int bits = 0; bits < (1 << TOOLSET_NAMES.length); bits++) {
                Set<String> toolsets = new LinkedHashSet<>();
                for (int index = 0; index < TOOLSET_NAMES.length; index++) {
                    if ((bits & (1 << index)) != 0) {
                        toolsets.add(TOOLSET_NAMES[index]);
                    }
                }
                ToolSurface surface =
                        ToolSurface.resolve(Map.of(ToolSurface.TOOLSETS_ENV, String.join(",", toolsets)), stress);
                Connection connection = new Connection(server, Caller.nowhere(), surface);

                String instructions = Instructions.forServer(connection);
                int bytes = instructions.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(
                        bytes <= CLAUDE_CODE_INSTRUCTIONS_MAX_BYTES,
                        "toolsets=" + toolsets + ": " + bytes + " bytes exceeds Claude Code's "
                                + CLAUDE_CODE_INSTRUCTIONS_MAX_BYTES + "-byte instructions ceiling");
            }
        }
    }

    private static TmuxTransport noOpTransport() {
        return new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest request) {
                return new CommandResult(0, List.of(), List.of());
            }

            @Override
            public void close() {}
        };
    }
}
