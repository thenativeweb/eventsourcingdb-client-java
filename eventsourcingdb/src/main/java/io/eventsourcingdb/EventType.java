package io.eventsourcingdb;

import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * An event type, as {@link Client#readEventTypes()} and {@link Client#readEventType(String)} return it.
 *
 * @param eventType the name of the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
 * @param isPhantom whether no event of this type was written yet, i.e. whether the type exists only because a schema
 *     is registered for it
 * @param schema the JSON schema registered for the event type, if any
 */
public record EventType(String eventType, boolean isPhantom, Optional<Map<String, Object>> schema) {
    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {};

    static EventType from(JsonNode eventType) {
        var schema = eventType.path("schema");

        return new EventType(
                eventType.path("eventType").asString(),
                eventType.path("isPhantom").asBoolean(),
                schema.isObject() ? Optional.of(Json.MAPPER.convertValue(schema, SCHEMA_TYPE)) : Optional.empty());
    }
}
