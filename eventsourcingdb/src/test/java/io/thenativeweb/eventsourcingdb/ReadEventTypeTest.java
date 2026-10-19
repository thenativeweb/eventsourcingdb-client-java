package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadEventTypeTest {
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

    @Test
    void failsIfTheEventTypeDoesNotExist() {
        var exception =
                assertThrows(DbApiException.class, () -> client.readEventType("io.eventsourcingdb.test.nonexistent"));

        assertEquals(ErrorCode.EVENT_TYPE_UNKNOWN, exception.code());
        assertFalse(exception.description().isEmpty());
    }

    @Test
    void failsIfTheEventTypeIsMalformed() {
        var exception = assertThrows(DbApiException.class, () -> client.readEventType("io.eventsourcingdb.test."));

        // The v1 API refuses the request with a status code. The v2 API
        // accepts a request of the right shape, and reports a value that is
        // malformed in its answer.
        if (Database.offersV2Api(container)) {
            assertEquals(200, exception.status());
            assertEquals(ErrorCode.EVENT_TYPE_MALFORMED, exception.code());
        } else {
            assertEquals(400, exception.status());
            assertEquals("", exception.code());
        }
        assertFalse(exception.description().isEmpty());
    }

    @Test
    void readsAnExistingEventType() {
        Map<String, Object> schema = Map.of(
                "type",
                "object",
                "properties",
                Map.of("value", Map.of("type", "number")),
                "required",
                List.of("value"),
                "additionalProperties",
                false);
        client.registerEventSchema("io.eventsourcingdb.test.foo", schema);

        var eventType = client.readEventType("io.eventsourcingdb.test.foo");

        assertEquals(new EventType("io.eventsourcingdb.test.foo", false, Optional.of(schema)), eventType);
    }
}
