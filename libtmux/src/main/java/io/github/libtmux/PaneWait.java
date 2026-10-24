package io.github.libtmux;

import io.github.libtmux.exception.DispatchException;
import io.github.libtmux.exception.LibTmuxException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Polls a pane on a timer for {@link Pane#awaitText} and {@link Pane#await}. */
final class PaneWait {

    /**
     * How often a wait looks again.
     *
     * <p>Short enough that a wait reports promptly, long enough that a wait held open for minutes
     * is not thousands of tmux invocations. A caller who needs an exact moment wants a signal on a
     * {@link Channel}, not a shorter interval here.
     */
    static final Duration POLL = Duration.ofMillis(50);

    private static final Duration SHORTEST_POLL = Duration.ofMillis(10);

    /**
     * The least a wait's first read is given, and the budget for telling a dead server from a read
     * that failed.
     *
     * <p>A quarter of a second, what {@link Channel#drain} gives a pending signal to come straight
     * back: long enough for tmux to answer, short enough not to be a wait.
     */
    private static final Duration SHORTEST_READ = Duration.ofMillis(250);

    private PaneWait() {}

    /** {@link Pane#watchText(String, Duration, Duration)}'s implementation. */
    static TextWait awaitText(Pane pane, String text, Duration timeout, Duration every) throws InterruptedException {
        TypedText typed = TypedText.in(pane);
        boolean[] reading = {true, false};
        AtomicReference<List<String>> seen = new AtomicReference<>(List.of());
        Ended ended = awaitCondition(
                pane,
                bounded -> {
                    List<String> lines = shown(bounded, typed);
                    seen.set(lines);
                    boolean found = lines.stream().anyMatch(line -> line.contains(text));
                    if (reading[0]) {
                        reading[0] = false;
                        reading[1] = found;
                    }
                    return found;
                },
                timeout,
                every,
                true);
        TextOutcome outcome =
                switch (ended) {
                    case SIGNALLED -> reading[1] ? TextOutcome.PRESENT_AT_ENTRY : TextOutcome.APPEARED;
                    case TIMED_OUT -> TextOutcome.TIMED_OUT;
                    case SERVER_GONE -> TextOutcome.SERVER_GONE;
                    case PANE_GONE -> TextOutcome.PANE_GONE;
                };
        return new TextWait(outcome, withoutTrailingBlanks(seen.get()));
    }

    private static List<String> withoutTrailingBlanks(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return List.copyOf(lines.subList(0, end));
    }

    /** {@link Pane#await(Predicate, Duration, Duration)}'s implementation. */
    static WakeReason await(Pane pane, Predicate<Pane> settled, Duration timeout, Duration every)
            throws InterruptedException {
        return switch (awaitCondition(
                pane, bounded -> settled.test(bounded.refresh().through(pane.server())), timeout, every, false)) {
            case SIGNALLED -> WakeReason.SIGNALLED;
            case TIMED_OUT -> WakeReason.TIMED_OUT;
            case SERVER_GONE -> WakeReason.SERVER_GONE;
            case PANE_GONE -> throw new IllegalStateException("a wait that rethrows a dead pane ended as one");
        };
    }

    /** How a wait ended, before the public wait maps it to its own outcome type. */
    private enum Ended {
        SIGNALLED,
        TIMED_OUT,
        SERVER_GONE,
        PANE_GONE
    }

    /**
     * One deadline for both public waits, applied to the reads as well as to the gaps between them.
     *
     * <p>Each read goes through {@link Server#within} with what is left of the deadline, so a read
     * cannot outlast the wait. A read that runs out of that budget has reached the wait's own
     * deadline, and is a timeout. Once the deadline has passed no further read starts, which is what
     * keeps text that arrives late from being reported.
     *
     * <p>An ordinary timeout asks tmux nothing more. The last read answered, so the server was there
     * a poll interval ago, and a liveness probe after the deadline would only spend time the caller
     * did not give. A read that <em>failed</em> is different: tmux reports "no server" and "no such
     * pane" the same way, so that one gets a second look to tell {@link WakeReason#SERVER_GONE} from a
     * failure that belongs to the caller.
     */
    private static Ended awaitCondition(
            Pane pane, Predicate<Pane> poll, Duration timeout, Duration every, boolean paneGoneIsAnAnswer)
            throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(every, "every");
        if (every.compareTo(SHORTEST_POLL) < 0) {
            throw new IllegalArgumentException("looking more often than every " + SHORTEST_POLL.toMillis()
                    + " ms spends a tmux process per look for no reading a person could tell apart: " + every);
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout is negative: " + timeout);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean first = true;
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException(
                        "interrupted while waiting on pane " + pane.id().value());
            }
            Duration left = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            Duration budget = first && left.compareTo(SHORTEST_READ) < 0 ? SHORTEST_READ : left;
            first = false;
            try {
                if (poll.test(pane.through(pane.server().within(budget)))) {
                    return Ended.SIGNALLED;
                }
            } catch (DispatchException.TimedOut expired) {
                return Ended.TIMED_OUT;
            } catch (LibTmuxException unreadable) {
                return afterFailedRead(pane, unreadable, paneGoneIsAnAnswer);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Ended.TIMED_OUT;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(every.toNanos(), remaining));
            if (System.nanoTime() >= deadline) {
                return Ended.TIMED_OUT;
            }
        }
    }

    /**
     * Tells a server that went away, and a pane that did, from a read that failed for a reason of the
     * caller's.
     *
     * <p>A probe that cannot get an answer in time proves nothing either way, so the original failure
     * is what the caller sees, carrying the probe's. A pane counts as gone only when the server
     * answers and no longer lists it; the error text is not read, because tmux words it differently
     * across releases.
     */
    private static Ended afterFailedRead(Pane pane, LibTmuxException unreadable, boolean paneGoneIsAnAnswer) {
        try {
            if (!pane.server().isAlive(SHORTEST_READ)) {
                return Ended.SERVER_GONE;
            }
            if (paneGoneIsAnAnswer
                    && pane.server().within(SHORTEST_READ).panes().stream()
                            .noneMatch(listed -> listed.id().equals(pane.id()))) {
                return Ended.PANE_GONE;
            }
        } catch (DispatchException.TimedOut unanswered) {
            unreadable.addSuppressed(unanswered);
        }
        throw unreadable;
    }

    /**
     * What a wait has to read, and different from {@link Pane#capture()} twice over. Wrapped rows are
     * rejoined, because a terminal breaks a long line wherever the pane happens to end and text split
     * across that break is in no single row — a wait watching for it would never see it while it sits
     * in plain view. And an echo of what this library just typed is taken out, because a terminal
     * shows the caller's own command back and a wait for something that command's text contains would
     * otherwise be answered by the question.
     */
    private static List<String> shown(Pane pane, TypedText typed) {
        return typed.withoutEcho(pane.capture(CaptureSpec.Builder::joiningWrappedLines));
    }
}
