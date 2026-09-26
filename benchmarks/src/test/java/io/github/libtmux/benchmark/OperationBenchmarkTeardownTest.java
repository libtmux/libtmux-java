package io.github.libtmux.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The harness rather than a measurement, so it runs with the ordinary suite. */
final class OperationBenchmarkTeardownTest {

    @Test
    void aScenarioThatThrowsStillEndsItsServer(@TempDir Path directory) {
        assertThrows(
                IllegalStateException.class,
                () -> new OperationBenchmark().once(directory, "throws", 0, server -> {}, server -> {
                    throw new IllegalStateException("the scenario failed");
                }));

        String socket = directory.resolve("throws-0").resolve("s").toString();
        assertFalse(
                ProcessHandle.allProcesses()
                        .anyMatch(process -> List.of(process.info().arguments().orElse(new String[0]))
                                .contains(socket)),
                "a tmux server outlived the scenario on " + socket);
    }
}
