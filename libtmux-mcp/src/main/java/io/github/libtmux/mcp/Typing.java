package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Putting input into a pane that is not a command to run.
 *
 * <p>Interrupting something, answering a prompt, driving a full-screen program. A command a model
 * wrote goes through {@link RunningCommands} instead, which waits for it and reports how it ended;
 * typing one here sends it and learns nothing.
 */
final class Typing {

    private Typing() {}

    record Sent(
            String paneId,
            int keys,
            boolean literal,
            List<String> resolvedPaneIds,
            @Nullable String note) {}

    record Pasted(
            String paneId,
            int characters,
            int lines,
            @Nullable String note) {}

    /**
     * The keys that can only stop what is running: each raises a signal at the terminal and enters
     * no text, so letting one through a run's held pane cannot interleave with what that run typed.
     */
    private static final Set<String> STOPS = Set.of("C-c", "C-\\");

    /**
     * Sends keys as tmux names them, so {@code C-c} interrupts and {@code Enter} is a keypress.
     *
     * <p>Names by default rather than literal text, because that is the only thing this tool can do
     * that the others cannot. Text that must arrive exactly as written, brackets and all, is what
     * {@code literal} is for.
     *
     * <p>{@code literal} governs the keys named here, never {@code enter}: tmux's own {@code -l}
     * treats every one of its arguments as literal text, so a caller sending {@code ["Enter"]} in a
     * second, separate {@code literal:true} call types the word instead of pressing it -
     * the trap this flag exists to make unnecessary. {@code enter} always presses the key.
     */
    static Sent sendKeys(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        List<String> keys = call.strings("keys");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "'keys' is empty; give the key names to send, such as [\"C-c\"] or [\"q\"]");
        }
        boolean literal = call.flag("literal", false);
        boolean enter = call.flag("enter", false);
        return sendKeys(pane, keys, literal, enter, PaneInputCohort.resolve(pane, call.caller()));
    }

    static Sent sendKeys(Pane pane, List<String> keys, boolean literal) {
        return sendKeys(pane, keys, literal, false, PaneInputCohort.resolve(pane));
    }

    static Sent sendKeys(Pane pane, List<String> keys, boolean literal, PaneInputCohort.Resolution cohort) {
        return sendKeys(pane, keys, literal, false, cohort);
    }

    static Sent sendKeys(
            Pane pane, List<String> keys, boolean literal, boolean enter, PaneInputCohort.Resolution cohort) {
        // Keys that only signal go through a run still holding this pane; anything that could type
        // waits for it. Without this the advice a timed-out run gives - interrupt it - names the one
        // thing this server refuses, and a command that hangs can never be stopped through it.
        boolean stopping = !literal && !enter && keys.stream().allMatch(STOPS::contains);
        try (PaneInputReservations.Lease lease = stopping
                ? PaneInputReservations.interrupting(cohort, "send_keys")
                : PaneInputReservations.keys(cohort, "send_keys")) {
            PaneInputCohort.Resolution fresh = PaneInputCohort.resolve(pane, cohort.caller());
            List<String> resolved = lease.requireSameKeys(fresh);
            // A boolean is right here and wrong on Pane: this one is a tool argument off the wire,
            // not a choice a reader of this file makes.
            if (literal) {
                pane.sendLiteral(keys);
                // sendLiteral records the echo for the pane it addressed. Under synchronize-panes the
                // same keys land in every pane of the window, which only the cohort knows about, so
                // the rest are told here.
                String typed = String.join("", keys);
                resolved.stream()
                        .filter(id -> !id.equals(pane.id().value()))
                        .forEach(id -> Targets.pane(pane.server(), id).noteTyped(typed));
            } else {
                pane.sendKeys(keys);
            }
            if (enter) {
                // A keypress, sent by name, inside the same reservation as the text above - nothing
                // else can interleave between typing a line and submitting it, and this can never be
                // the "-l typed the word Enter" trap because it never goes through sendLiteral.
                pane.sendKeys(List.of("Enter"));
            }
            return new Sent(
                    pane.id().value(),
                    keys.size(),
                    literal,
                    resolved,
                    "Sent, not waited for. A command you authored is run_shell_command's job, which frames and"
                            + " waits for it correctly; reach for capture_since or wait_for_text here only for input"
                            + " that is not a command to run to completion. Either way, a wait_for_text pattern that"
                            + " repeats text from these keys can match the pane's own echo of them rather than what"
                            + " runs.");
        }
    }

    /**
     * Puts text into a pane through a paste buffer rather than as keystrokes.
     *
     * <p>Which is what an editor, a REPL or anything reading a here-document needs: pasted text is
     * delivered as one block with no key names looked up in it, so a line containing {@code Enter}
     * or a bracket arrives as those characters.
     *
     * <p>The buffer tmux needs travels with the paste that consumes it, so a disconnected client
     * cannot leave the text in the paste history a person shares with the model.
     */
    static Pasted pasteText(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        String text = call.stringIncludingEmpty("text");
        boolean enter = call.flag("enter", false);
        PaneInputCohort.Resolution initial = PaneInputCohort.resolve(pane, call.caller());
        try (PaneInputReservations.Lease lease = PaneInputReservations.paste(initial, "paste_text")) {
            if (text.isEmpty() && !enter) {
                return new Pasted(pane.id().value(), 0, 0, "Empty text without Enter; nothing was sent.");
            }
            // tmux turns the line feeds in a buffer into carriage returns as it pastes, so a trailing
            // newline is what submits the text — there is no flag that means "and Enter".
            pane.paste(
                    enter ? text + "\n" : text,
                    () -> lease.requireSamePaste(PaneInputCohort.resolve(pane, call.caller())));
            return new Pasted(
                    pane.id().value(),
                    text.length(),
                    (int) text.lines().count(),
                    enter ? null : "Pasted without a trailing newline; pass 'enter' to submit it.");
        }
    }
}
