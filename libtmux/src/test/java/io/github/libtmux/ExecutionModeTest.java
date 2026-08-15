package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Choosing a carrier from outside the program that uses one. */
final class ExecutionModeTest {

    @Test
    void aPropertyNamesTheCarrier() {
        assertEquals(Optional.of(ExecutionMode.CONTROL), ExecutionMode.of(withMode("control"), Map.of()));
    }

    @Test
    void anEnvironmentVariableNamesTheCarrier() {
        assertEquals(
                Optional.of(ExecutionMode.VIRTUAL),
                ExecutionMode.of(new Properties(), Map.of("LIBTMUX_MODE", "virtual")));
    }

    @Test
    void aPropertyBeatsTheEnvironment() {
        assertEquals(
                Optional.of(ExecutionMode.DIRECT),
                ExecutionMode.of(withMode("direct"), Map.of("LIBTMUX_MODE", "control")),
                "a flag passed to this JVM is more specific than the environment it inherited");
    }

    @Test
    void sayingNothingIsNotADecision() {
        assertEquals(Optional.empty(), ExecutionMode.of(new Properties(), Map.of()));
    }

    @Test
    void anEmptyValueIsNotADecision() {
        assertEquals(
                Optional.empty(),
                ExecutionMode.of(new Properties(), Map.of("LIBTMUX_MODE", "  ")),
                "an unset variable is commonly spelled as an empty one");
    }

    @Test
    void anOperatorMayTypeItHowever() {
        assertEquals(Optional.of(ExecutionMode.CONTROL), ExecutionMode.of(withMode("  Control "), Map.of()));
    }

    @Test
    void aMisspelledPropertyIsLoudRatherThanSilentlyIgnored() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> ExecutionMode.of(withMode("contro"), Map.of()));

        String message = String.valueOf(thrown.getMessage());
        assertTrue(message.contains("libtmux.mode"), message);
        assertTrue(message.contains("contro"), message);
        assertTrue(message.contains("CONTROL"), message);
    }

    @Test
    void aMisspelledEnvironmentValueNamesTheVariableToFix() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionMode.of(new Properties(), Map.of("LIBTMUX_MODE", "controll")));

        String message = String.valueOf(thrown.getMessage());
        assertTrue(message.contains("LIBTMUX_MODE"), message);
    }

    private static Properties withMode(String mode) {
        Properties properties = new Properties();
        properties.setProperty("libtmux.mode", mode);
        return properties;
    }
}
