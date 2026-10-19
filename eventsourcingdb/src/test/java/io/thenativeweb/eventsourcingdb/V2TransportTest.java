package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The v2 API answers writes with a stream, which ends with an end item, and
// reports an error in its answer with a code.
class V2TransportTest {
    private static final ApiVersion API = ApiVersion.V2;

    @Test
    void writesEventsWhoseAnswerArrivesAfterHeartbeats() {
        try (var server =
                FakeServer.streaming(API, Lines.heartbeat(API), Lines.event(API), Lines.EVENT_OF_THE_V2_API)) {
            var events = new Client(server.url(), "secret")
                    .writeEvents(List.of(new EventCandidate("https://x", "/a", "io.x.y", Map.of())));

            assertEquals(2, events.size());
            assertEquals(
                    Lines.SPECIAL_CHARACTERS, events.getFirst().data().path("z").asString());
            events.getFirst().verifyHash();
            events.getLast().verifyHash();
        }
    }

    @Test
    void registersAnEventSchema() {
        var registered = API.item(
                "event",
                "{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"https://www.eventsourcingdb.io\","
                        + "\"type\":\"io.eventsourcingdb.api.event-schema-registered\","
                        + "\"data\":{\"type\":\"io.x.y\",\"schema\":{\"type\":\"object\"}}}");

        try (var server = FakeServer.streaming(API, Lines.heartbeat(API), registered)) {
            var client = new Client(server.url(), "secret");

            assertDoesNotThrow(() -> client.registerEventSchema("io.x.y", Map.of("type", "object")));
        }
    }

    @Test
    void reportsAnEventTypeThatDoesNotExistWithTheErrorInTheAnswer() {
        var error = API.item(
                "error",
                "{\"code\":\"" + ErrorCode.EVENT_TYPE_UNKNOWN + "\",\"description\":\"event type is unknown\","
                        + "\"details\":{\"value\":\"io.x.y\"}}");

        try (var server = FakeServer.respondingWith(API, 200, error)) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, () -> client.readEventType("io.x.y"));

            assertEquals("failed to read event type, got error: event type is unknown", exception.getMessage());
            assertEquals(200, exception.status());
            assertEquals(ErrorCode.EVENT_TYPE_UNKNOWN, exception.code());
            assertEquals("io.x.y", exception.details().path("value").asString());
        }
    }

    @Test
    void failsOnAnAnswerToReadingAnEventTypeOfAnotherType() {
        try (var server = FakeServer.respondingWith(API, 200, Lines.subject(API))) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(EventSourcingDbException.class, () -> client.readEventType("io.x.y"));

            assertEquals("failed to handle unsupported line type: subject", exception.getMessage());
        }
    }

    @Test
    void failsOnAPingThatIsAnsweredWithSomethingOtherThanAnEvent() {
        try (var server = FakeServer.respondingWith(API, 200, Lines.error(API))) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(EventSourcingDbException.class, client::ping);

            assertEquals("failed to ping", exception.getMessage());
        }
    }
}
