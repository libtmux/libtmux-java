package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Reading what tmux tells a process running inside it.
 *
 * <p>Every case here passes the variables in rather than setting them, so the parsing is decided
 * without a process ever being started inside tmux. The shapes below are what the release matrix
 * actually exports, not what the manual describes.
 */
final class TmuxEnvironmentTest {

    /** The exact shape observed on every lane from 3.2a to 3.7b. */
    private static Map<String, String> inside() {
        return Map.of("TMUX", "/tmp/libtmux-abc/s,2821815,0", "TMUX_PANE", "%0");
    }

    @Test
    void theVariablesTmuxExportsAreReadBackWhole() {
        TmuxEnvironment here = TmuxEnvironment.of(inside()).orElseThrow();

        assertEquals(Path.of("/tmp/libtmux-abc/s"), here.socket());
        assertEquals(2821815L, here.serverPid());
        assertEquals(new SessionId("$0"), here.session());
        assertEquals(Optional.of(new PaneId("%0")), here.pane());
    }

    /**
     * tmux writes the session field bare, while every id read back from a listing carries its
     * sigil. Without adding it, the id would never equal the session it names.
     */
    @Test
    void theBareSessionNumberBecomesAnIdThatMatchesOne() {
        TmuxEnvironment here = TmuxEnvironment.of(inside()).orElseThrow();

        assertEquals(new SessionId("$0"), here.session(), "a listing reports $0, not 0");
    }

    @Test
    void aSessionFieldThatAlreadyCarriesItsSigilIsNotGivenASecond() {
        TmuxEnvironment here = TmuxEnvironment.of(Map.of("TMUX", "/tmp/s,1,$3")).orElseThrow();

        assertEquals(new SessionId("$3"), here.session());
    }

    /** A socket path may contain commas, so the two numbers are taken from the end. */
    @Test
    void aSocketPathWithCommasInItSurvivesTheSplit() {
        TmuxEnvironment here =
                TmuxEnvironment.of(Map.of("TMUX", "/tmp/od,d/s,42,7")).orElseThrow();

        assertEquals(Path.of("/tmp/od,d/s"), here.socket());
        assertEquals(42L, here.serverPid());
        assertEquals(new SessionId("$7"), here.session());
    }

    @Test
    void aProcessOutsideTmuxSeesNothing() {
        assertTrue(TmuxEnvironment.of(Map.of()).isEmpty());
        assertTrue(TmuxEnvironment.of(Map.of("TMUX", "")).isEmpty(), "tmux clears it rather than unsetting it");
    }

    @Test
    void anUnreadableValueIsAbsentRatherThanAFailure() {
        assertTrue(TmuxEnvironment.of(Map.of("TMUX", "nonsense")).isEmpty());
        assertTrue(TmuxEnvironment.of(Map.of("TMUX", "/tmp/s,notapid,0")).isEmpty());
        assertTrue(TmuxEnvironment.of(Map.of("TMUX", "/tmp/s,1,")).isEmpty());
    }

    /** A process can inherit TMUX without TMUX_PANE, and still knows which server it is on. */
    @Test
    void thePaneIsAbsentWithoutItsOwnVariable() {
        TmuxEnvironment here = TmuxEnvironment.of(Map.of("TMUX", "/tmp/s,1,0")).orElseThrow();

        assertTrue(here.pane().isEmpty());
        assertEquals(new SessionId("$0"), here.session(), "the session is still known");
    }

    @Test
    void theConfigItHandsBackAddressesTheSocketTmuxNamed() {
        ServerConfig config = TmuxEnvironment.of(inside()).orElseThrow().config();

        assertEquals(ServerEndpoint.socketPath(Path.of("/tmp/libtmux-abc/s")), config.endpoint());
    }

    @Test
    void readingThisProcessIsTheSameAsReadingItsEnvironment() {
        assertEquals(
                TmuxEnvironment.of(System.getenv()).isPresent(),
                TmuxEnvironment.current().isPresent(),
                "current() is of(System.getenv()) and nothing else");
    }

    /** The suite is quarantined from the developer's tmux, so nothing here should look inside one. */
    @Test
    void theTestSuiteItselfIsNotRunningInsideTmux() {
        assertFalse(TmuxEnvironment.current().isPresent(), "a fixture would address the developer's own server");
    }
}
