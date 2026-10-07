package io.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.eventsourcingdb.testcontainers.Container;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WriteEventsTest {
    record EventData(int value) {}

    private Container container;
    private Client client;

    @BeforeEach
    void startContainer() {
        container = Database.start();
        client = container.getClient();
    }

    @AfterEach
    void stopContainer() {
        container.stop();
    }

    private static EventCandidate candidate(int value) {
        return new EventCandidate(
                "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", new EventData(value));
    }

    @Test
    void writesASingleEvent() {
        var writtenEvents = client.writeEvents(List.of(candidate(42)));

        assertEquals(1, writtenEvents.size());

        var event = writtenEvents.getFirst();
        assertEquals("0", event.id());
        assertEquals("1.0", event.specVersion());
        assertEquals("https://www.eventsourcingdb.io", event.source());
        assertEquals("/test", event.subject());
        assertEquals("io.eventsourcingdb.test", event.type());
        assertEquals("application/json", event.dataContentType());
        assertEquals(new EventData(42), event.data(EventData.class));
        assertEquals(42, event.data().path("value").asInt());
        assertEquals(64, event.hash().length());
        assertEquals("0".repeat(64), event.predecessorHash());
        assertTrue(Duration.between(event.time(), Instant.now()).abs().toMinutes() < 1);
        assertEquals(Optional.empty(), event.traceParent());
        assertEquals(Optional.empty(), event.traceState());
        assertEquals(Optional.empty(), event.signature());
    }

    @Test
    void writesMultipleEvents() {
        var writtenEvents = client.writeEvents(List.of(candidate(23), candidate(42)));

        assertEquals(2, writtenEvents.size());
        assertEquals("0", writtenEvents.get(0).id());
        assertEquals(new EventData(23), writtenEvents.get(0).data(EventData.class));
        assertEquals("1", writtenEvents.get(1).id());
        assertEquals(new EventData(42), writtenEvents.get(1).data(EventData.class));
    }

    @Test
    void writesTheTraceContextOfAnEvent() {
        var traceParent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        var traceState = "rojo=00f067aa0ba902b7";

        var writtenEvents = client.writeEvents(List.of(new EventCandidate(
                "https://www.eventsourcingdb.io",
                "/test",
                "io.eventsourcingdb.test",
                new EventData(42),
                traceParent,
                traceState)));

        assertEquals(Optional.of(traceParent), writtenEvents.getFirst().traceParent());
        assertEquals(Optional.of(traceState), writtenEvents.getFirst().traceState());
    }

    @Test
    void supportsTheIsSubjectPristinePrecondition() {
        client.writeEvents(List.of(candidate(23)));

        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(List.of(candidate(42)), List.of(new IsSubjectPristinePrecondition("/test"))));
        assertEquals(409, exception.statusCode());
    }

    @Test
    void supportsTheIsSubjectPopulatedPrecondition() {
        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(List.of(candidate(42)), List.of(new IsSubjectPopulatedPrecondition("/test"))));
        assertEquals(409, exception.statusCode());

        client.writeEvents(List.of(candidate(23)));
        var writtenEvents =
                client.writeEvents(List.of(candidate(42)), List.of(new IsSubjectPopulatedPrecondition("/test")));
        assertEquals(1, writtenEvents.size());
    }

    @Test
    void supportsTheIsSubjectOnEventIdPrecondition() {
        client.writeEvents(List.of(candidate(23)));

        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(
                        List.of(candidate(42)), List.of(new IsSubjectOnEventIdPrecondition("/test", "1"))));
        assertEquals(409, exception.statusCode());

        var writtenEvents =
                client.writeEvents(List.of(candidate(42)), List.of(new IsSubjectOnEventIdPrecondition("/test", "0")));
        assertEquals(1, writtenEvents.size());
    }

    @Test
    void supportsTheIsEventQlQueryTruePrecondition() {
        client.writeEvents(List.of(candidate(23)));

        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(
                        List.of(candidate(42)),
                        List.of(new IsEventQlQueryTruePrecondition("FROM e IN events PROJECT INTO COUNT() == 0"))));
        assertEquals(409, exception.statusCode());
    }

    @Test
    void reportsTheReasonIfAnEventDoesNotMatchItsSchema() {
        client.registerEventSchema(
                "io.eventsourcingdb.test",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("value", Map.of("type", "number")),
                        "required",
                        List.of("value"),
                        "additionalProperties",
                        false));

        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(List.of(new EventCandidate(
                        "https://www.eventsourcingdb.io",
                        "/test",
                        "io.eventsourcingdb.test",
                        Map.of("value", 23, "extra", true)))));

        assertEquals(
                "failed to write events, got HTTP status code '409', expected '200': schema conflict: event candidate does not match schema: additionalProperties 'extra' not allowed",
                exception.getMessage());
        assertEquals("write events", exception.action());
        assertEquals(409, exception.statusCode());
        assertEquals(
                "schema conflict: event candidate does not match schema: additionalProperties 'extra' not allowed",
                exception.reason());
    }
}
