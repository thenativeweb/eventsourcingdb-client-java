package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// The v1 API tells whether an event type has no events by isPhantom, which
// the client turns around into hasEvents, as the v2 API sends it.
class EventTypeTest {
    private static final List<EventType> EVENT_TYPES = List.of(
            new EventType("io.x.a", true, Optional.empty()),
            new EventType("io.x.b", false, Optional.of(Map.of("type", "object"))));

    private static List<EventType> readEventTypes(FakeServer server) {
        try (var eventTypes = new Client(server.url(), "secret").readEventTypes()) {
            return eventTypes.toList();
        }
    }

    @Test
    void tellsWhetherAnEventTypeOfTheV1ApiHasEvents() {
        var api = ApiVersion.V1;

        try (var server = FakeServer.streaming(
                api,
                api.item("eventType", "{\"eventType\":\"io.x.a\",\"isPhantom\":false}"),
                api.item(
                        "eventType",
                        "{\"eventType\":\"io.x.b\",\"isPhantom\":true,\"schema\":{\"type\":\"object\"}}"))) {
            assertEquals(EVENT_TYPES, readEventTypes(server));
        }
    }

    @Test
    void tellsWhetherAnEventTypeOfTheV2ApiHasEvents() {
        var api = ApiVersion.V2;

        try (var server = FakeServer.streaming(
                api,
                api.item("eventType", "{\"type\":\"io.x.a\",\"hasEvents\":true,\"schema\":null}"),
                api.item("eventType", "{\"type\":\"io.x.b\",\"hasEvents\":false,\"schema\":{\"type\":\"object\"}}"))) {
            assertEquals(EVENT_TYPES, readEventTypes(server));
        }
    }

    @Test
    void tellsWhetherASingleEventTypeOfTheV1ApiHasEvents() {
        try (var server =
                FakeServer.respondingWith(ApiVersion.V1, 200, "{\"eventType\":\"io.x.a\",\"isPhantom\":true}")) {
            var eventType = new Client(server.url(), "secret").readEventType("io.x.a");

            assertEquals(new EventType("io.x.a", false, Optional.empty()), eventType);
        }
    }

    @Test
    void tellsWhetherASingleEventTypeOfTheV2ApiHasEvents() {
        try (var server = FakeServer.respondingWith(
                ApiVersion.V2,
                200,
                ApiVersion.V2.item("eventType", "{\"type\":\"io.x.a\",\"hasEvents\":false,\"schema\":null}"))) {
            var eventType = new Client(server.url(), "secret").readEventType("io.x.a");

            assertEquals(new EventType("io.x.a", false, Optional.empty()), eventType);
        }
    }
}
