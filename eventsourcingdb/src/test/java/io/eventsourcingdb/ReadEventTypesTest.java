package io.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadEventTypesTest {
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

    private List<EventType> readEventTypes() {
        try (var eventTypes = client.readEventTypes()) {
            return eventTypes.toList();
        }
    }

    @Test
    void readsNoEventTypesIfTheDatabaseIsEmpty() {
        assertEquals(List.of(), readEventTypes());
    }

    @Test
    void readsAllEventTypes() {
        client.writeEvents(List.of(
                new EventCandidate("https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test.foo", Map.of()),
                new EventCandidate(
                        "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test.bar", Map.of())));

        assertEquals(
                List.of(
                        new EventType("io.eventsourcingdb.test.bar", false, Optional.empty()),
                        new EventType("io.eventsourcingdb.test.foo", false, Optional.empty())),
                readEventTypes());
    }

    @Test
    void supportsReadingEventSchemas() {
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "number")),
                "required",
                List.of("value"),
                "additionalProperties",
                false);
        client.registerEventSchema("io.eventsourcingdb.test", schema);

        assertEquals(List.of(new EventType("io.eventsourcingdb.test", true, Optional.of(schema))), readEventTypes());
    }
}
