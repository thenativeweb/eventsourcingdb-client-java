package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadEventsTest {
    record EventData(int value) {}

    record HugeEventData(String value) {}

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

    private static EventCandidate candidate(String subject, String type, Object data) {
        return new EventCandidate("https://www.eventsourcingdb.io", subject, type, data);
    }

    private List<Event> read(String subject, ReadEventsOptions options) {
        try (var events = client.readEvents(subject, options)) {
            return events.toList();
        }
    }

    private void writeTwoEvents() {
        client.writeEvents(List.of(
                candidate("/test/1", "io.eventsourcingdb.test", new EventData(23)),
                candidate("/test/2", "io.eventsourcingdb.test", new EventData(42))));
    }

    @Test
    void readsNoEventsIfTheDatabaseIsEmpty() {
        assertEquals(List.of(), read("/", new ReadEventsOptions(true)));
    }

    @Test
    void readsAllEventsFromTheGivenSubject() {
        writeTwoEvents();

        var eventsRead = read("/test/1", new ReadEventsOptions(false));

        assertEquals(1, eventsRead.size());
        assertEquals("0", eventsRead.getFirst().id());
        assertEquals(new EventData(23), eventsRead.getFirst().data(EventData.class));
        eventsRead.getFirst().verifyHash();
    }

    @Test
    void readsAHugeEventWithoutTruncatingItsPayload() {
        // The server escapes each of these characters with six, so the event
        // stays within its limit of 64 KiB, but exceeds any small buffer.
        var value = "<&>".repeat(3_000);
        client.writeEvents(List.of(candidate("/test", "io.eventsourcingdb.test", new HugeEventData(value))));

        var eventsRead = read("/test", new ReadEventsOptions(false));

        assertEquals(1, eventsRead.size());
        assertEquals(new HugeEventData(value), eventsRead.getFirst().data(HugeEventData.class));
        eventsRead.getFirst().verifyHash();
    }

    @Test
    void readsRecursively() {
        writeTwoEvents();

        var eventsRead = read("/test", new ReadEventsOptions(true));

        assertEquals(List.of("0", "1"), eventsRead.stream().map(Event::id).toList());
    }

    @Test
    void readsChronologically() {
        writeTwoEvents();

        var eventsRead = read("/test", new ReadEventsOptions(true).withOrder(Order.CHRONOLOGICAL));

        assertEquals(List.of("0", "1"), eventsRead.stream().map(Event::id).toList());
    }

    @Test
    void readsAntichronologically() {
        writeTwoEvents();

        var eventsRead = read("/test", new ReadEventsOptions(true).withOrder(Order.ANTICHRONOLOGICAL));

        assertEquals(List.of("1", "0"), eventsRead.stream().map(Event::id).toList());
    }

    @Test
    void readsWithLowerBound() {
        writeTwoEvents();

        var eventsRead = read("/test", new ReadEventsOptions(true).withLowerBound(new Bound("1", BoundType.INCLUSIVE)));

        assertEquals(List.of("1"), eventsRead.stream().map(Event::id).toList());
    }

    @Test
    void readsWithUpperBound() {
        writeTwoEvents();

        var eventsRead = read("/test", new ReadEventsOptions(true).withUpperBound(new Bound("1", BoundType.EXCLUSIVE)));

        assertEquals(List.of("0"), eventsRead.stream().map(Event::id).toList());
    }

    @Test
    void readsFromLatestEvent() {
        client.writeEvents(List.of(
                candidate("/test", "io.eventsourcingdb.test.foo", new EventData(23)),
                candidate("/test", "io.eventsourcingdb.test.bar", new EventData(42))));

        var eventsRead = read(
                "/test",
                new ReadEventsOptions(false)
                        .withFromLatestEvent(new ReadFromLatestEvent(
                                "/test", "io.eventsourcingdb.test.bar", ReadIfEventIsMissing.READ_EVERYTHING)));

        assertEquals(1, eventsRead.size());
        assertEquals(new EventData(42), eventsRead.getFirst().data(EventData.class));
    }

    @Test
    void readsNothingIfTheLatestEventIsMissing() {
        writeTwoEvents();

        var eventsRead = read(
                "/test",
                new ReadEventsOptions(true)
                        .withFromLatestEvent(new ReadFromLatestEvent(
                                "/test", "io.eventsourcingdb.test.missing", ReadIfEventIsMissing.READ_NOTHING)));

        assertEquals(List.of(), eventsRead);
    }
}
