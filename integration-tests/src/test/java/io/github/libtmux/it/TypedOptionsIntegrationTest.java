package io.github.libtmux.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.OptionKey;
import io.github.libtmux.Server;
import io.github.libtmux.Session;
import io.github.libtmux.Window;
import io.github.libtmux.exception.LibTmuxException;
import io.github.libtmux.junit5.TmuxExtension;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * An option read as the type it is declared as, against the tmux each lane runs.
 *
 * <p>Real tmux rather than a fake, because what is being checked is what tmux reports: a key's type
 * is only right if tmux's own text for that option parses as it.
 */
@ExtendWith(TmuxExtension.class)
final class TypedOptionsIntegrationTest {

    @Test
    void aNumberAndAFlagRoundTripAsTheirTypes(Server server) {
        Session session = server.sessions().get(0);

        session.options().set(OptionKey.HISTORY_LIMIT, 50_000);
        session.options().set(OptionKey.MOUSE, true);

        assertEquals(Optional.of(50_000), session.options().get(OptionKey.HISTORY_LIMIT));
        assertEquals(Optional.of(true), session.options().get(OptionKey.MOUSE));

        session.options().set(OptionKey.MOUSE, false);
        assertEquals(Optional.of(false), session.options().get(OptionKey.MOUSE));
    }

    /**
     * Each constant reads at its own scope without error, which is the only proof its declared type
     * matches what this tmux reports for it.
     */
    @Test
    void everyConstantReadsAsItsDeclaredTypeOnThisTmux(Server server) {
        Session session = server.sessions().get(0);
        Window window = session.windows().get(0);

        assertTrue(session.options().get(OptionKey.HISTORY_LIMIT).isPresent());
        assertTrue(session.options().get(OptionKey.BASE_INDEX).isPresent());
        assertTrue(session.options().get(OptionKey.MOUSE).isPresent());
        assertTrue(server.options().get(OptionKey.ESCAPE_TIME).isPresent());
        assertTrue(window.options().get(OptionKey.AUTOMATIC_RENAME).isPresent());
        assertTrue(window.options().get(OptionKey.SYNCHRONIZE_PANES).isPresent());
    }

    /** A key declared with the wrong type fails loudly, naming the option, rather than guessing. */
    @Test
    void aKeyDeclaredWithTheWrongTypeSaysSo(Server server) {
        LibTmuxException wrong = assertThrows(
                LibTmuxException.class, () -> server.globalOptions().get(OptionKey.number("status")));

        assertTrue(String.valueOf(wrong.getMessage()).contains("status"), "the option is named: " + wrong.getMessage());
    }

    /** A hook command given as words reaches tmux as those words, quotes and formats included. */
    @Test
    void aHookGivenAsWordsIsBoundAsThoseWords(Server server) {
        Session session = server.sessions().get(0);

        session.hooks().set("after-new-window", List.of("display-message", "it's #{window_name}"));

        List<String> bound = session.hooks().all().getOrDefault("after-new-window", List.of());
        assertEquals(1, bound.size(), "one command bound: " + bound);
        assertTrue(bound.getFirst().contains("it's #{window_name}"), "the quote and the format survived: " + bound);
    }
}
