package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.util.List;
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
     * Sends keys as tmux names them, so {@code C-c} interrupts and {@code Enter} is a keypress.
     *
     * <p>Names by default rather than literal text, because that is the only thing this tool can do
     * that the others cannot. Text that must arrive exactly as written, brackets and all, is what
     * {@code literal} is for.
     */
    static Sent sendKeys(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        List<String> keys = call.strings("keys");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "'keys' is empty; give the key names to send, such as [\"C-c\"] or [\"q\"]");
        }
        boolean literal = call.flag("literal", false);
        return sendKeys(pane, keys, literal, PaneInputCohort.resolve(pane, call.caller()));
    }

    static Sent sendKeys(Pane pane, List<String> keys, boolean literal) {
        PaneInputCohort.Resolution cohort = PaneInputCohort.resolve(pane);
        return sendKeys(pane, keys, literal, cohort);
    }

    static Sent sendKeys(Pane pane, List<String> keys, boolean literal, PaneInputCohort.Resolution cohort) {
        List<String> resolved = cohort.requireKeyRecipients("send_keys");
        pane.sendKeys(keys, literal);
        return new Sent(
                pane.id().value(),
                keys.size(),
                literal,
                resolved,
                "Sent, not waited for. Call capture_since or wait_for_text on this pane to see " + "what it did.");
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
        PaneInputCohort.resolve(pane, call.caller()).requirePasteTarget("paste_text");
        if (text.isEmpty() && !enter) {
            return new Pasted(pane.id().value(), 0, 0, "Empty text without Enter; nothing was sent.");
        }
        // tmux turns the line feeds in a buffer into carriage returns as it pastes, so a trailing
        // newline is what submits the text — there is no flag that means "and Enter".
        pane.paste(
                enter ? text + "\n" : text,
                () -> PaneInputCohort.resolve(pane, call.caller()).requirePasteTarget("paste_text"));
        return new Pasted(
                pane.id().value(),
                text.length(),
                (int) text.lines().count(),
                enter ? null : "Pasted without a trailing newline; pass 'enter' to submit it.");
    }
}
