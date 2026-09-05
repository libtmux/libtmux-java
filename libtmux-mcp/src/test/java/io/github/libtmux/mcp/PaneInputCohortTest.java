package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.libtmux.LibTmuxException;
import io.github.libtmux.Server;
import io.github.libtmux.format.RowFormat;
import io.github.libtmux.format.TmuxFormatException;
import io.github.libtmux.junit5.TmuxExtension;
import io.github.libtmux.transport.CommandRequest;
import io.github.libtmux.transport.CommandResult;
import io.github.libtmux.transport.ProcessTransport;
import io.github.libtmux.transport.TmuxTransport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TmuxExtension.class)
final class PaneInputCohortTest {

    private static final RowFormat TOKENS = RowFormat.of("separator");
    private static final String SEPARATOR = TOKENS.separator();
    private static final String TERMINATOR = TOKENS.template().substring("#{separator}".length());

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorityFailures")
    void commandAndSourceFailuresFailClosed(String label, String source, CommandResult result) {
        assertThrows(LibTmuxException.class, () -> PaneInputCohort.parse(source, result), label);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRows")
    void malformedAuthoritativeRowsFailClosed(String label, List<String> stdout) {
        assertThrows(
                TmuxFormatException.class,
                () -> PaneInputCohort.parse("%0", new CommandResult(0, stdout, List.of())),
                label);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "junk", "$1", " %1", "\n%1", "%00", "%01", "%4294967296",
        "%9999999999999999999999999999999999999999"
    })
    void noncanonicalPaneIdsFailClosed(String paneId) {
        assertThrows(
                TmuxFormatException.class,
                () -> PaneInputCohort.parse("%0", answer(row("%0", "1", "0", "0", "sh"),
                        row(paneId, "1", "0", "0", "sh"))));
    }

    @Test
    void strayPhysicalLineCannotBecomePaneId() {
        assertThrows(
                TmuxFormatException.class,
                () -> PaneInputCohort.parse("%0", answer(
                        row("%0", "1", "0", "0", "sh"),
                        "junk",
                        row("%1", "1", "0", "0", "sh"))));
    }

    @Test
    void maximumPaneIdPeerIsAccepted() {
        var resolved = PaneInputCohort.parse("%0", answer(
                row("%0", "1", "0", "0", "sh"),
                row("%4294967295", "1", "0", "0", "sh")));

        assertEquals(List.of("%0", "%4294967295"), resolved.configuredKeyRecipientIds());
    }

    @Test
    void sourceFlagDeterminesTheEffectiveCohort() {
        var sourceOff = PaneInputCohort.parse("%10", answer(
                row("%1", "1", "0", "0", "sh"),
                row("%10", "0", "0", "0", "sh")));
        var sourceOn = PaneInputCohort.parse("%10", answer(
                row("%10", "1", "0", "0", "sh"),
                row("%1", "0", "0", "0", "sh"),
                row("%0", "1", "0", "0", "sh")));

        assertEquals(List.of("%10"), sourceOff.configuredKeyRecipientIds());
        assertEquals(List.of("%0", "%10"), sourceOn.configuredKeyRecipientIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "1", "2"})
    void noncanonicalOrPositiveModesAreModal(String mode) {
        var resolved = PaneInputCohort.parse("%0", answer(row("%0", "0", mode, "0", "sh")));

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> resolved.requireKeyRecipients("send_keys"));
        assertTrue(String.valueOf(refused.getMessage()).contains("%0"));
    }

    @Test
    void deadConfiguredMembersFailButDeadNonmembersDoNot() {
        var deadSource = PaneInputCohort.parse("%0", answer(row("%0", "0", "0", "1", "sh")));
        var deadPeer = PaneInputCohort.parse("%0", answer(
                row("%0", "1", "0", "0", "sh"),
                row("%1", "1", "0", "1", "sh")));
        var outside = PaneInputCohort.parse("%0", answer(
                row("%0", "0", "0", "0", "sh"),
                row("%1", "1", "0", "1", "sh")));

        assertThrows(IllegalStateException.class, () -> deadSource.requireKeyRecipients("send_keys"));
        assertThrows(IllegalStateException.class, () -> deadPeer.requireKeyRecipients("send_keys"));
        assertEquals(List.of("%0"), outside.requireKeyRecipients("send_keys"));
    }

    @Test
    void pasteChecksOnlyTheSourceWhileCommandsRequireOneRecipient() {
        var sourceOnly = PaneInputCohort.parse("%0", answer(
                row("%0", "0", "0", "0", "/bin/sh"),
                row("%1", "1", "2", "1", "cat")));
        var plural = PaneInputCohort.parse("%0", answer(
                row("%0", "1", "0", "0", "/bin/sh"),
                row("%1", "1", "0", "0", "sh")));

        sourceOnly.requirePasteTarget("paste_text");
        assertEquals("/bin/sh", sourceOnly.requireSingularCommandPane("run_shell_command"));
        assertThrows(
                IllegalStateException.class,
                () -> plural.requireSingularCommandPane("run_shell_command"));
    }

    @Test
    void resolutionUsesOneTargetedFiveFieldListing(Server server) {
        CopyOnWriteArrayList<CommandRequest> requests = new CopyOnWriteArrayList<>();
        try (ProcessTransport processes = new ProcessTransport()) {
            TmuxTransport recording = new TmuxTransport() {
                @Override
                public CommandResult execute(CommandRequest request) {
                    requests.add(request);
                    return processes.execute(request);
                }

                @Override
                public void close() {}
            };
            try (Server measured = Server.using(server.config(), recording)) {
                var pane = measured.panes().getFirst();
                requests.clear();

                var resolved = PaneInputCohort.resolve(pane);

                assertEquals(List.of(pane.id().value()), resolved.configuredKeyRecipientIds());
            }
        }

        List<List<String>> commands = requests.stream().flatMap(request -> request.commands().stream()).toList();
        assertEquals(1, commands.size());
        List<String> listing = commands.getFirst();
        assertEquals(List.of("list-panes", "-t"), listing.subList(0, 2));
        assertTrue(listing.contains("-F"));
        String format = listing.get(listing.indexOf("-F") + 1);
        for (String field : List.of(
                "pane_id", "pane_synchronized", "pane_in_mode", "pane_dead", "pane_current_command")) {
            assertEquals(1, occurrences(format, "#{" + field + "}"));
        }
    }

    private static Stream<Arguments> authorityFailures() {
        return Stream.of(
                Arguments.of("command failed", "%0", new CommandResult(1, List.of(), List.of("gone"))),
                Arguments.of("no rows", "%0", answer()),
                Arguments.of("source absent", "%0", answer(row("%1", "0", "0", "0", "sh"))),
                Arguments.of("source duplicated", "%0", answer(
                        row("%0", "0", "0", "0", "sh"),
                        row("%0", "0", "0", "0", "sh"))));
    }

    private static Stream<Arguments> malformedRows() {
        String complete = row("%0", "0", "0", "0", "sh");
        String peerWithoutTerminator = fields("%1", "0", "0", "0", "sh");
        return Stream.of(
                Arguments.of("empty pane id", List.of(row("", "0", "0", "0", "sh"))),
                Arguments.of("empty current command", List.of(row("%0", "0", "0", "0", ""))),
                Arguments.of("too few fields", List.of(fields("%0", "0", "0", "0") + TERMINATOR)),
                Arguments.of("no terminator", List.of(fields("%0", "0", "0", "0", "sh"))),
                Arguments.of("unterminated final row", List.of(complete, peerWithoutTerminator)),
                Arguments.of("data after terminator", List.of(complete + "tail")),
                Arguments.of("extra physical data", List.of(complete, "tail")),
                Arguments.of("empty mode", List.of(row("%0", "0", "", "0", "sh"))),
                Arguments.of("word mode", List.of(row("%0", "0", "on", "0", "sh"))),
                Arguments.of("negative mode", List.of(row("%0", "0", "-1", "0", "sh"))),
                Arguments.of("word synchronized", List.of(row("%0", "on", "0", "0", "sh"))),
                Arguments.of("word dead", List.of(row("%0", "0", "0", "on", "sh"))));
    }

    private static CommandResult answer(String... rows) {
        return new CommandResult(0, List.of(rows), List.of());
    }

    private static String row(String... fields) {
        return fields(fields) + TERMINATOR;
    }

    private static String fields(String... fields) {
        return String.join(SEPARATOR, fields);
    }

    private static int occurrences(String value, String wanted) {
        return (value.length() - value.replace(wanted, "").length()) / wanted.length();
    }
}
