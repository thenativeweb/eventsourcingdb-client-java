package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The v1 API answers a write with the events it wrote, as a JSON array, and a
// registration with the event that tells of it.
class V1TransportTest {
    private static final ApiVersion API = ApiVersion.V1;

    @Test
    void writesEvents() {
        try (var server = FakeServer.respondingWith(API, 200, "[" + Lines.EVENT_PAYLOAD + "]")) {
            var events = new Client(server.url(), "secret")
                    .writeEvents(List.of(new EventCandidate("https://x", "/a", "io.x.y", Map.of())));

            assertEquals(1, events.size());
            assertEquals(
                    Lines.SPECIAL_CHARACTERS, events.getFirst().data().path("z").asString());
            events.getFirst().verifyHash();
        }
    }

    @Test
    void registersAnEventSchema() {
        try (var server =
                FakeServer.respondingWith(API, 200, "{\"type\":\"io.eventsourcingdb.api.event-schema-registered\"}")) {
            var client = new Client(server.url(), "secret");

            assertDoesNotThrow(() -> client.registerEventSchema("io.x.y", Map.of("type", "object")));
        }
    }
}
