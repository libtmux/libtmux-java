package com.git_pull.libtmux.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.git_pull.libtmux.Server;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * What the extension promises: a private tmux per test, and no tmux left behind.
 *
 * <p>These cases run a nested engine rather than asserting from inside a fixture. Teardown is only
 * worth trusting if it survives a test that failed, and a test cannot watch its own teardown.
 */
final class TmuxExtensionTest {

    /** Sockets the nested tests were handed, so the outer case can check what became of them. */
    static final List<Path> ISSUED = Collections.synchronizedList(new ArrayList<>());

    @Test
    void aTestGetsItsOwnLiveServer() {
        ISSUED.clear();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(PassingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertEquals(1, ISSUED.size());
    }

    @Test
    void everyServerIsGoneOnceItsTestEnds() throws Exception {
        ISSUED.clear();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(PassingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(1));

        assertTrue(dead(ISSUED.get(0)), "the fixture server outlived the test that owned it");
    }

    /** Teardown that only runs when a test passes is teardown that cannot be relied on. */
    @Test
    void aFailingTestStillHasItsServerReclaimed() throws Exception {
        ISSUED.clear();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(FailingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.failed(1));

        assertEquals(1, ISSUED.size(), "the fixture was created before the test failed");
        assertTrue(dead(ISSUED.get(0)), "a failing test must not leak its tmux");
    }

    @Test
    void theOriginalFailureSurvivesTeardown() {
        ISSUED.clear();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(FailingFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(
                        1,
                        org.junit.platform.testkit.engine.EventConditions.finishedWithFailure(
                                org.junit.platform.testkit.engine.TestExecutionResultConditions.message(
                                        message -> message.contains("deliberate"))));
    }

    @Test
    void separateTestsNeverShareAServer() {
        ISSUED.clear();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(TwoTestFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.succeeded(2));

        assertEquals(2, ISSUED.size());
        assertNotEquals(ISSUED.get(0), ISSUED.get(1), "one socket for two tests is shared state");
    }

    private static boolean dead(Path socket) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!Files.exists(socket)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    // -------------------------------------------------------------------------------- fixtures

    @Tag("fixture")
    @ExtendWith(TmuxExtension.class)
    static class PassingFixture {

        @Test
        void theServerIsLiveAndIsTheOneWeWerePromised(Server server, TmuxSocketPath socket) {
            ISSUED.add(socket.path());

            assertEquals(
                    List.of(socket.path().toString()),
                    server.cmd("display-message", "-p", "#{socket_path}").stdout(),
                    "tmux must agree about which socket it is listening on");
            assertTrue(Files.exists(socket.path()), "the promised socket does not physically exist");
            assertFalse(
                    server.cmd("list-sessions", "-F", "#{session_name}")
                            .stdout()
                            .isEmpty(),
                    "the fixture promises a detached session to work with");
        }
    }

    @Tag("fixture")
    @ExtendWith(TmuxExtension.class)
    static class FailingFixture {

        @Test
        void thisOneFails(TmuxSocketPath socket) {
            ISSUED.add(socket.path());
            throw new AssertionError("deliberate failure, to watch teardown run anyway");
        }
    }

    @Tag("fixture")
    @ExtendWith(TmuxExtension.class)
    static class TwoTestFixture {

        @Test
        void first(TmuxSocketPath socket) {
            ISSUED.add(socket.path());
        }

        @Test
        void second(TmuxSocketPath socket) {
            ISSUED.add(socket.path());
        }
    }
}
