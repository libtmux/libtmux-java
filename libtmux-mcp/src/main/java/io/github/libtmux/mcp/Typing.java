package io.github.libtmux.mcp;

import io.github.libtmux.Pane;
import java.util.ArrayList;
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
        List<String> argv = new ArrayList<>(List.of("send-keys"));
        if (literal) {
            argv.add("-l");
        }
        argv.addAll(List.of("-t", pane.id().value()));
        argv.addAll(keys);
        call.server().run(argv);
        return new Sent(
                pane.id().value(),
                keys.size(),
                literal,
                "Sent, not waited for. Call tmux_capture_since or tmux_wait_for_text on this pane to see "
                        + "what it did.");
    }

    /**
     * Puts text into a pane through a paste buffer rather than as keystrokes.
     *
     * <p>Which is what an editor, a REPL or anything reading a here-document needs: pasted text is
     * delivered as one block with no key names looked up in it, so a line containing {@code Enter}
     * or a bracket arrives as those characters.
     *
     * <p>The buffer is named for this call and deleted afterwards, so nothing is left in the paste
     * history a person shares with the model.
     */
    static Pasted pasteText(Call call) {
        Pane pane = Targets.pane(call.server(), call.string("pane_id"));
        String text = call.string("text");
        boolean enter = call.flag("enter", false);
        String buffer = "libtmux-mcp-paste";
        try {
            // tmux turns the line feeds in a buffer into carriage returns as it pastes, so a
            // trailing newline is what submits the text — there is no flag that means "and Enter".
            call.server().buffers().set(buffer, enter ? text + "\n" : text);
            // -d removes the buffer as part of the paste, so nothing is left in the paste history a
            // person shares with the model even if this call is the last thing that runs.
            call.server()
                    .run(List.of(
                            "paste-buffer", "-d", "-b", buffer, "-t", pane.id().value()));
        } catch (RuntimeException e) {
            try {
                call.server().buffers().delete(buffer);
            } catch (RuntimeException ignored) {
                // Already gone, or the server is; neither changes what the caller is told.
            }
            throw e;
        }
        return new Pasted(
                pane.id().value(),
                text.length(),
                (int) text.lines().count(),
                enter ? null : "Pasted without a trailing newline; pass 'enter' to submit it.");
    }
}
