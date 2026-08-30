package io.github.libtmux.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.libtmux.batch.OperationOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ControlProtocolTest {

    @Test
    void onlyTheFullOpeningGuardCanCloseAReply() {
        ControlProtocol protocol = new ControlProtocol();

        assertInstanceOf(ControlProtocol.Awaiting.class, protocol.accept("%begin 100 7 1"));
        assertInstanceOf(ControlProtocol.Awaiting.class, protocol.accept("%end 1 2 3"));
        assertInstanceOf(ControlProtocol.Awaiting.class, protocol.accept("%begin 9 9 9"));
        ControlProtocol.Reply reply = assertInstanceOf(ControlProtocol.Reply.class, protocol.accept("%end 100 7 1"));

        assertEquals(OperationOutcome.COMPLETE, reply.outcome());
        assertEquals(List.of("%end 1 2 3", "%begin 9 9 9"), reply.lines());
    }

    @Test
    void matchingErrorGuardsFailTheReplyAndNotificationsStayOutsideIt() {
        ControlProtocol protocol = new ControlProtocol();

        ControlProtocol.Notification notification =
                assertInstanceOf(ControlProtocol.Notification.class, protocol.accept("%window-add @1"));
        assertEquals("%window-add @1", notification.line());
        protocol.accept("%begin 100 8 1");
        protocol.accept("no such window");
        ControlProtocol.Reply reply = assertInstanceOf(ControlProtocol.Reply.class, protocol.accept("%error 100 8 1"));

        assertEquals(OperationOutcome.FAILED, reply.outcome());
        assertEquals(List.of("no such window"), reply.lines());
    }

    @Test
    void requestEncodingKeepsLineBreaksInsideOneArgument() {
        assertEquals(
                "'set-buffer' 'first'\"\\n\"'second'\"\\r\"'third'",
                ControlProtocol.line(List.of("set-buffer", "first\nsecond\rthird")));
        assertThrows(
                IllegalArgumentException.class, () -> ControlProtocol.line(List.of("set-buffer", "before\0after")));
    }

    @Test
    void aReplyCannotGrowPastItsAggregateByteLimit() {
        ControlProtocol protocol = new ControlProtocol(8);
        protocol.accept("%begin 100 7 1");
        protocol.accept("1234", 4);

        assertThrows(ControlProtocol.LimitExceeded.class, () -> protocol.accept("5678", 4));
    }
}
