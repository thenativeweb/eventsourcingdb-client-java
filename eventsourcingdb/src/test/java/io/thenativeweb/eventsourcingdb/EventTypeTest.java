package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// The v1 API tells whether an event type has no events by isPhantom, which
// the client turns around into hasEvents, as the v2 API sends it.
class EventTypeTest {
    @Test
    void tellsWhetherAnEventTypeOfTheV1ApiHasEvents() {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(
                            exchange, "{\"type\":\"eventType\",\"payload\":{\"eventType\":\"io.x.a\",\"isPhantom\":false}}");
                    FakeServer.writeLine(
                            exchange,
                            "{\"type\":\"eventType\",\"payload\":{\"eventType\":\"io.x.b\",\"isPhantom\":true,"
                                    + "\"schema\":{\"type\":\"object\"}}}");
                });
                var eventTypes = new Client(server.url(), "secret").readEventTypes()) {
            assertEquals(
                    List.of(
                            new EventType("io.x.a", true, Optional.empty()),
                            new EventType("io.x.b", false, Optional.of(Map.of("type", "object")))),
                    eventTypes.toList());
        }
    }

    @Test
    void tellsWhetherASingleEventTypeOfTheV1ApiHasEvents() {
        try (var server = FakeServer.respondingWith(200, "{\"eventType\":\"io.x.a\",\"isPhantom\":true}")) {
            var eventType = new Client(server.url(), "secret").readEventType("io.x.a");

            assertEquals(new EventType("io.x.a", false, Optional.empty()), eventType);
        }
    }
}
