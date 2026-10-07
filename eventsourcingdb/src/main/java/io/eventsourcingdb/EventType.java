package io.eventsourcingdb;

import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

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
