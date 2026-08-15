package io.github.libtmux.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The virtual-thread carrier moves where a call parks; it must not change what the call answers. */
final class VirtualThreadTransportTest {

    private static final CommandRequest REQUEST =
            new CommandRequest(List.of("tmux"), List.of("list-windows"), Duration.ofSeconds(5));

    private record Fixed(CommandResult answer) implements TmuxTransport {
        @Override
        public CommandResult execute(CommandRequest ignored) {
            return answer;
        }

        @Override
        public void close() {}
    }

    private record Throwing(RuntimeException failure) implements TmuxTransport {
        @Override
        public CommandResult execute(CommandRequest ignored) {
            throw failure;
        }

        @Override
        public void close() {}
    }

    /** The worker runs the delegate; what the delegate answers is what the caller must get back. */
    @Test
    void theDelegatesAnswerIsTheCallersAnswer() {
        CommandResult answer = new CommandResult(0, List.of("one"), List.of());
        try (VirtualThreadTransport transport = new VirtualThreadTransport(new Fixed(answer))) {
            assertEquals(answer, transport.execute(REQUEST));
        }
    }

    @Test
    void aFailureCrossesTheWorkerUnchanged() {
        TmuxTransportException failure =
                new TmuxTransportException("tmux exceeded its deadline", DispatchOutcome.UNKNOWN, null);
        try (VirtualThreadTransport transport = new VirtualThreadTransport(new Throwing(failure))) {
            assertEquals(failure, assertThrows(TmuxTransportException.class, () -> transport.execute(REQUEST)));
        }
    }

    /**
     * An {@code Error} is not a {@code RuntimeException}, so a carrier that only rescues the latter
     * loses it: the worker dies with nothing recorded and the join returns normally. What the caller
     * then receives is no failure and no result — a null the whole library is declared not to have.
     */
    @Test
    void anErrorInTheWorkerReachesTheCallerRatherThanBecomingANullResult() {
        TmuxTransport erroring = new TmuxTransport() {
            @Override
            public CommandResult execute(CommandRequest ignored) {
                throw new UnknownError("the worker died");
            }

            @Override
            public void close() {}
        };
        try (VirtualThreadTransport transport = new VirtualThreadTransport(erroring)) {
            assertEquals(
                    "the worker died",
                    assertThrows(UnknownError.class, () -> transport.execute(REQUEST))
                            .getMessage());
        }
    }
}
