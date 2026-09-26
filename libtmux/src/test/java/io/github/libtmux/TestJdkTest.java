package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** A lane that names a JDK runs the suite on it; one that only hosted Gradle there proved nothing. */
final class TestJdkTest {

    @Test
    void theSuiteRunsOnTheJdkTheBuildNamed() {
        String named = System.getProperty("libtmux.testJdk");

        assertNotNull(named, "the build did not say which JDK it chose");
        assertEquals(Integer.parseInt(named), Runtime.version().feature());
    }
}
