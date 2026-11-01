package io.github.libtmux.testsandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SandboxCheckTest {

    private static final String SANDBOX = "/tmp/libtmux-java-test/0123456789abcdef";

    private static String refusalFor(Map<String, String> environment) {
        return Objects.requireNonNull(
                assertThrows(IllegalStateException.class, () -> SandboxCheck.require(environment, SANDBOX))
                        .getMessage());
    }

    private static Map<String, String> clean() {
        return Map.of("PATH", "/usr/bin", "TMUX_TMPDIR", SANDBOX);
    }

    @Test
    void aProcessTheBuildPreparedHasNothingToReport() {
        assertEquals(List.of(), SandboxCheck.findings(clean(), SANDBOX));
    }

    @Test
    void thisProcessIsOneTheBuildPrepared() {
        assertEquals(
                List.of(),
                SandboxCheck.findings(System.getenv(), System.getProperty(SandboxCheck.SANDBOX_PROPERTY)),
                "the build's own tests run outside the quarantine they ship");
    }

    @Test
    void anInheritedTmuxIsReportedEvenWhenEmpty() {
        Map<String, String> inside = Map.of("TMUX", "", "TMUX_TMPDIR", SANDBOX);

        assertEquals(1, SandboxCheck.findings(inside, SANDBOX).size());
        assertTrue(SandboxCheck.findings(inside, SANDBOX).getFirst().startsWith("TMUX is set"));
    }

    @Test
    void aPaneIsReported() {
        Map<String, String> pane = Map.of("TMUX_PANE", "%3", "TMUX_TMPDIR", SANDBOX);

        assertTrue(SandboxCheck.findings(pane, SANDBOX).getFirst().startsWith("TMUX_PANE is set"));
    }

    @Test
    void everySocketSelectorIsReportedByName() {
        Map<String, String> selected = Map.of(
                "LIBTMUX_SOCKET",
                "work",
                "LIBTMUX_SOCKET_PATH",
                "/tmp/s",
                "LIBTMUX_SOCKET_NAME",
                "x",
                "TMUX_TMPDIR",
                SANDBOX);

        List<String> found = SandboxCheck.findings(selected, SANDBOX);

        assertEquals(3, found.size());
        assertTrue(found.get(0).startsWith("LIBTMUX_SOCKET is set"));
        assertTrue(found.get(1).startsWith("LIBTMUX_SOCKET_NAME is set"));
        assertTrue(found.get(2).startsWith("LIBTMUX_SOCKET_PATH is set"));
    }

    @Test
    void aSimilarlyNamedVariableIsLeftAlone() {
        Map<String, String> other = Map.of("LIBTMUX_TOOLSETS", "inspect", "TMUX_TMPDIR", SANDBOX);

        assertEquals(List.of(), SandboxCheck.findings(other, SANDBOX));
    }

    @Test
    void aMissingMarkerMeansNoTaskPreparedTheProcess() {
        assertTrue(SandboxCheck.findings(clean(), null).getFirst().contains(SandboxCheck.SANDBOX_PROPERTY));
        assertEquals(1, SandboxCheck.findings(clean(), "").size());
    }

    @Test
    void aTmuxTmpdirOtherThanTheOneTheBuildPreparedIsReported() {
        assertEquals(
                List.of("TMUX_TMPDIR is not the directory the build prepared for this task"),
                SandboxCheck.findings(Map.of("TMUX_TMPDIR", "/tmp"), SANDBOX));
        assertEquals(List.of("TMUX_TMPDIR is unset"), SandboxCheck.findings(Map.of(), SANDBOX));
    }

    @Test
    void noFindingAndNoRefusalPrintsAValue() {
        Map<String, String> secrets = Map.of(
                "TMUX", "value-of-tmux",
                "TMUX_PANE", "value-of-pane",
                "LIBTMUX_SOCKET", "value-of-socket",
                "LIBTMUX_SOCKET_PATH", "value-of-socket-path",
                "TMUX_TMPDIR", "value-of-tmpdir");

        String findings = String.join("\n", SandboxCheck.findings(secrets, SANDBOX));
        String refusal = refusalFor(secrets);

        assertFalse(findings.contains("value-of"), "a finding printed a variable's value");
        assertFalse(refusal.contains("value-of"), "the refusal printed a variable's value");
        assertTrue(findings.contains("TMUX_TMPDIR"), "the control did not report the variables it was given");
    }

    @Test
    void theRefusalNamesEveryFindingAndTheWayOut() {
        String message = refusalFor(Map.of("TMUX", "/tmp/s,1,0"));

        assertTrue(message.contains("TMUX is set"));
        assertTrue(message.contains("TMUX_TMPDIR is unset"));
        assertTrue(message.contains("./gradlew"));
    }

    @Test
    void requireReturnsForACleanProcess() {
        SandboxCheck.require(clean(), SANDBOX);
    }
}
