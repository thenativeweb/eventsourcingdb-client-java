package io.thenativeweb.eventsourcingdb;

import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * An event type, as {@link Client#readEventTypes()} and {@link Client#readEventType(String)} return it.
 *
 * @param type the name of the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
 * @param hasEvents whether events of this type were written, which is not the case if the type exists only because a
 *     schema is registered for it
 * @param schema the JSON schema registered for the event type, if any
 */
public record EventType(String type, boolean hasEvents, Optional<Map<String, Object>> schema) {
    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {};

    // The v1 API tells by isPhantom whether an event type has no events.
    static EventType fromV1(JsonNode eventType) {
        return new EventType(
                eventType.path("eventType").asString(),
                !eventType.path("isPhantom").asBoolean(),
                schemaOf(eventType.path("schema")));
    }

    // A schema is an object, if there is one. The v2 API sends null for an
    // event type without a schema, and the v1 API leaves it out.
    static Optional<Map<String, Object>> schemaOf(JsonNode schema) {
        return schema.isObject() ? Optional.of(Json.MAPPER.convertValue(schema, SCHEMA_TYPE)) : Optional.empty();
    }
}
