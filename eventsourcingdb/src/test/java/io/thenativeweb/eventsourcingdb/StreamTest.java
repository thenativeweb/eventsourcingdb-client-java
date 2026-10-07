package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.core.JacksonException;

@Timeout(10)
class StreamTest {
    private static FakeServer streaming(String... lines) {
        return FakeServer.start((exchange, server) -> {
            FakeServer.startStream(exchange);
            for (var line : lines) {
                FakeServer.writeLine(exchange, line);
            }
        });
    }

    @Test
    void readsAnEventExactlyAsTheServerSentIt() {
        try (var server = streaming(Lines.EVENT);
                var events = new Client(server.url(), "secret").readEvents("/a", new ReadEventsOptions(false))) {
            var event = events.toList().getFirst();

            assertEquals(Lines.SPECIAL_CHARACTERS, event.data().path("z").asString());
            assertEquals(Optional.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"), event.traceParent());
            event.verifyHash();
        }
    }

    @Test
    void skipsBlankLinesAndHeartbeats() {
        try (var server = streaming("", Lines.HEARTBEAT, Lines.SUBJECT);
                var subjects = new Client(server.url(), "secret").readSubjects("/")) {
            assertEquals(List.of("/"), subjects.toList());
        }
    }

    @Test
    void endsWithTheErrorTheServerSends() {
        try (var server = streaming(
                        Lines.SUBJECT, "{\"type\":\"error\",\"payload\":{\"error\":\"something went wrong\"}}");
                var subjects = new Client(server.url(), "secret").readSubjects("/")) {
            var subjectsRead = new ArrayList<String>();

            var exception = assertThrows(EventSourcingDbException.class, () -> subjects.forEach(subjectsRead::add));

            assertEquals(List.of("/"), subjectsRead);
            assertEquals("failed to read subjects, got error: something went wrong", exception.getMessage());
        }
    }

    @Test
    void failsOnAnUnsupportedLineType() {
        try (var server = streaming("{\"type\":\"something-else\"}");
                var subjects = new Client(server.url(), "secret").readSubjects("/")) {
            var exception = assertThrows(EventSourcingDbException.class, subjects::toList);

            assertEquals("failed to handle unsupported line type: something-else", exception.getMessage());
        }
    }

    @Test
    void failsOnAMalformedLine() {
        try (var server = streaming("{\"type\":");
                var subjects = new Client(server.url(), "secret").readSubjects("/")) {
            assertThrows(JacksonException.class, subjects::toList);
        }
    }

    @Test
    void failsIfTheConnectionBreaksInTheMiddle() {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
                    exchange.sendResponseHeaders(200, 1000);
                    FakeServer.writeLine(exchange, Lines.SUBJECT);
                });
                var subjects = new Client(server.url(), "secret").readSubjects("/")) {
            var subjectsRead = new ArrayList<String>();

            var exception = assertThrows(EventSourcingDbException.class, () -> subjects.forEach(subjectsRead::add));

            assertEquals(List.of("/"), subjectsRead);
            assertEquals("failed to read subjects", exception.getMessage());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }
}
