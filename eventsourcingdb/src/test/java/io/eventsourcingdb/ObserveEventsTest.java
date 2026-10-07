package io.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.eventsourcingdb.testcontainers.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ObserveEventsTest {
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

    private static EventCandidate candidate(String subject, String type, int value) {
        return new EventCandidate("https://www.eventsourcingdb.io", subject, type, new EventData(value));
    }

    private void writeTwoEvents() {
        client.writeEvents(List.of(
                candidate("/test/1", "io.eventsourcingdb.test", 23),
                candidate("/test/2", "io.eventsourcingdb.test", 42)));
    }

    private List<Event> observe(String subject, ObserveEventsOptions options, int count) {
        try (var events = client.observeEvents(subject, options)) {
            return events.limit(count).toList();
        }
    }

    @Test
    void observesNoEventsIfTheDatabaseIsEmpty() {
        var eventsObserved = new ArrayList<Event>();

        try (var events = client.observeEvents("/", new ObserveEventsOptions(true));
                var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(events::close, 500, TimeUnit.MILLISECONDS);

            assertThrows(CancellationException.class, () -> events.forEach(eventsObserved::add));
        }

        assertEquals(List.of(), eventsObserved);
    }

    @Test
    void observesAllEventsFromTheGivenSubject() {
        writeTwoEvents();

        var eventsObserved = observe("/test/1", new ObserveEventsOptions(false), 1);

        assertEquals(1, eventsObserved.size());
        assertEquals(new EventData(23), eventsObserved.getFirst().data(EventData.class));
        eventsObserved.getFirst().verifyHash();
    }

    @Test
    void observesEventsThatAreWrittenWhileObserving() {
        try (var events = client.observeEvents("/test", new ObserveEventsOptions(true));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(this::writeTwoEvents);

            var eventsObserved = events.limit(2).toList();

            assertEquals(
                    List.of("0", "1"), eventsObserved.stream().map(Event::id).toList());
        }
    }

    @Test
    void observesWithLowerBound() {
        writeTwoEvents();

        var eventsObserved =
                observe("/test", new ObserveEventsOptions(true).withLowerBound(new Bound("1", BoundType.INCLUSIVE)), 1);

        assertEquals("1", eventsObserved.getFirst().id());
    }

    @Test
    void observesFromLatestEvent() {
        client.writeEvents(List.of(
                candidate("/test", "io.eventsourcingdb.test.foo", 23),
                candidate("/test", "io.eventsourcingdb.test.bar", 42)));

        var eventsObserved = observe(
                "/test",
                new ObserveEventsOptions(false)
                        .withFromLatestEvent(new ObserveFromLatestEvent(
                                "/test", "io.eventsourcingdb.test.bar", ObserveIfEventIsMissing.READ_EVERYTHING)),
                1);

        assertEquals(new EventData(42), eventsObserved.getFirst().data(EventData.class));
    }

    @Test
    void waitsForTheLatestEventIfItIsMissing() {
        writeTwoEvents();

        try (var events = client.observeEvents(
                        "/test",
                        new ObserveEventsOptions(true)
                                .withFromLatestEvent(new ObserveFromLatestEvent(
                                        "/test/3",
                                        "io.eventsourcingdb.test.bar",
                                        ObserveIfEventIsMissing.WAIT_FOR_EVENT)));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> client.writeEvents(List.of(candidate("/test/3", "io.eventsourcingdb.test.bar", 7))));

            var eventsObserved = events.limit(1).toList();

            assertEquals(new EventData(7), eventsObserved.getFirst().data(EventData.class));
        }
    }
}
