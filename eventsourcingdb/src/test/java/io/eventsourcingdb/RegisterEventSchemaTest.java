package io.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegisterEventSchemaTest {
    private static final Map<String, Object> SCHEMA = Map.of(
            "type",
            "object",
            "properties",
            Map.of("value", Map.of("type", "number")),
            "required",
            List.of("value"),
            "additionalProperties",
            false);

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
    void registersAnEventSchema() {
        assertDoesNotThrow(() -> client.registerEventSchema("io.eventsourcingdb.test", SCHEMA));
    }

    @Test
    void throwsIfAnEventSchemaIsAlreadyRegistered() {
        client.registerEventSchema("io.eventsourcingdb.test", SCHEMA);

        var exception =
                assertThrows(DbApiException.class, () -> client.registerEventSchema("io.eventsourcingdb.test", SCHEMA));
        assertEquals(409, exception.statusCode());
    }
}
