package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
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
        return candidate("/test", value);
    }

    private static EventCandidate candidate(String subject, int value) {
        return new EventCandidate(
                "https://www.eventsourcingdb.io", subject, "io.eventsourcingdb.test", new EventData(value));
    }

    private void assertHolds(Precondition precondition) {
        var writtenEvents = client.writeEvents(List.of(candidate(42)), List.of(precondition));
        assertEquals(1, writtenEvents.size());
    }

    private void assertViolated(Precondition precondition) {
        var exception = assertThrows(
                DbApiException.class, () -> client.writeEvents(List.of(candidate(42)), List.of(precondition)));
        assertEquals(ErrorCode.PRECONDITION_VIOLATED, exception.code());
    }

    // A server without the v2 API can not check a recursive precondition, so
    // the client refuses the write without sending it. The tests hand over a
    // precondition that holds on the subject alone, so that a write sent
    // without the options would be stored.
    private void assertRefusedWithoutTheV2Api(Precondition precondition) {
        var eventCount = countEvents();

        var exception = assertThrows(
                IncompatibleServerException.class,
                () -> client.writeEvents(List.of(candidate(42)), List.of(precondition)));

        assertEquals(
                "the server does not offer the v2 API, which a recursive precondition needs", exception.getMessage());
        assertEquals(eventCount, countEvents());
    }

    private long countEvents() {
        try (var events = client.readEvents("/", new ReadEventsOptions(true))) {
            return events.count();
        }
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
        assertTrue(event.hash().matches("(esdb:hash:v2:)?[0-9a-f]{64}"), event.hash());
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
    void writesNothingIfTheThreadIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> client.writeEvents(List.of(candidate(23))));
        } finally {
            assertTrue(Thread.interrupted());
        }

        try (var events = client.readEvents("/", new ReadEventsOptions(true))) {
            assertEquals(0, events.count());
        }
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
        assertEquals(ErrorCode.PRECONDITION_VIOLATED, exception.code());
    }

    @Test
    void supportsTheIsSubjectPopulatedPrecondition() {
        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(List.of(candidate(42)), List.of(new IsSubjectPopulatedPrecondition("/test"))));
        assertEquals(ErrorCode.PRECONDITION_VIOLATED, exception.code());

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
        assertEquals(ErrorCode.PRECONDITION_VIOLATED, exception.code());

        var writtenEvents =
                client.writeEvents(List.of(candidate(42)), List.of(new IsSubjectOnEventIdPrecondition("/test", "0")));
        assertEquals(1, writtenEvents.size());
    }

    @Test
    void supportsTheIsSubjectPristinePreconditionRecursively() {
        var recursive = new PreconditionOptions(true);
        client.writeEvents(List.of(candidate("/test/1", 23)));

        // The subject has no events of its own, but a nested subject has.
        var precondition = new IsSubjectPristinePrecondition("/test", recursive);

        if (Database.offersV2Api(container)) {
            assertViolated(precondition);
            assertHolds(new IsSubjectPristinePrecondition("/test/2", recursive));
        } else {
            assertRefusedWithoutTheV2Api(precondition);
        }
    }

    @Test
    void supportsTheIsSubjectPopulatedPreconditionRecursively() {
        var recursive = new PreconditionOptions(true);
        client.writeEvents(List.of(candidate("/test/1", 23)));

        if (Database.offersV2Api(container)) {
            assertViolated(new IsSubjectPopulatedPrecondition("/test/2", recursive));

            // The subject has no events of its own, but a nested subject has.
            assertHolds(new IsSubjectPopulatedPrecondition("/test", recursive));
        } else {
            assertRefusedWithoutTheV2Api(new IsSubjectPopulatedPrecondition("/test/1", recursive));
        }
    }

    @Test
    void supportsTheIsSubjectOnEventIdPreconditionRecursively() {
        var recursive = new PreconditionOptions(true);
        client.writeEvents(List.of(candidate("/test", 23)));
        client.writeEvents(List.of(candidate("/test/1", 42)));

        // The last event of the subject itself is 0, but the last event among
        // the subject and its nested subjects is 1.
        var precondition = new IsSubjectOnEventIdPrecondition("/test", "0", recursive);

        if (Database.offersV2Api(container)) {
            assertViolated(precondition);
            assertHolds(new IsSubjectOnEventIdPrecondition("/test", "1", recursive));
        } else {
            assertRefusedWithoutTheV2Api(precondition);
        }
    }

    @Test
    void supportsTheIsSubjectPristinePreconditionWithOptionsThatAreNotRecursive() {
        var notRecursive = new PreconditionOptions(false);
        client.writeEvents(List.of(candidate("/test/1", 23)));

        assertHolds(new IsSubjectPristinePrecondition("/test", notRecursive));
        assertViolated(new IsSubjectPristinePrecondition("/test", notRecursive));
    }

    @Test
    void supportsTheIsSubjectPopulatedPreconditionWithOptionsThatAreNotRecursive() {
        var notRecursive = new PreconditionOptions(false);
        client.writeEvents(List.of(candidate("/test/1", 23)));

        assertViolated(new IsSubjectPopulatedPrecondition("/test", notRecursive));
        assertHolds(new IsSubjectPopulatedPrecondition("/test/1", notRecursive));
    }

    @Test
    void supportsTheIsSubjectOnEventIdPreconditionWithOptionsThatAreNotRecursive() {
        var notRecursive = new PreconditionOptions(false);
        client.writeEvents(List.of(candidate("/test", 23)));
        client.writeEvents(List.of(candidate("/test/1", 42)));

        assertViolated(new IsSubjectOnEventIdPrecondition("/test", "1", notRecursive));
        assertHolds(new IsSubjectOnEventIdPrecondition("/test", "0", notRecursive));
    }

    @Test
    void supportsTheIsEventQlQueryTruePrecondition() {
        client.writeEvents(List.of(candidate(23)));

        var exception = assertThrows(
                DbApiException.class,
                () -> client.writeEvents(
                        List.of(candidate(42)),
                        List.of(new IsEventQlQueryTruePrecondition("FROM e IN events PROJECT INTO COUNT() == 0"))));
        assertEquals(ErrorCode.PRECONDITION_VIOLATED, exception.code());
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

        assertEquals("write events", exception.action());
        assertEquals(ErrorCode.EVENT_SCHEMA_VIOLATED, exception.code());
        assertTrue(
                exception.description().contains("additionalProperties 'extra' not allowed"), exception.description());
        assertTrue(exception.getMessage().endsWith(exception.description()), exception.getMessage());
    }
}
