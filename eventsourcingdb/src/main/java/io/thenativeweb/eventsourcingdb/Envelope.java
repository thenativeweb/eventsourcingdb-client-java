package io.thenativeweb.eventsourcingdb;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

// Envelope tells how an API wraps the items it sends: each item names its
// type, and carries its payload under a key, which the v1 API calls payload
// and the v2 API data. An item of the type error reports an error, which the
// two APIs describe differently.
record Envelope(String payloadKey, ErrorItem errorItem) {
    static final Envelope V1 = new Envelope(
            "payload",
            (action, payload) -> DbApiException.ofAnswer(
                    action, "", payload.path("error").asString(), Json.MAPPER.createObjectNode()));

    static final Envelope V2 = new Envelope(
            "data",
            (action, payload) -> DbApiException.ofAnswer(
                    action,
                    payload.path("code").asString(),
                    payload.path("description").asString(),
                    payload.path("details")));

    @FunctionalInterface
    interface ErrorItem {
        DbApiException toException(String action, JsonNode payload);
    }

    // An item: its type, its payload, and the text of the data within its
    // payload as the server wrote it, if there is any.
    record Item(String type, JsonNode payload, @Nullable String rawData) {}

    // Reads an item in a single pass, since that is what most of the time of
    // reading a stream goes into.
    Item read(String json) {
        try (var parser = Json.MAPPER.createParser(json)) {
            var type = "";
            JsonNode payload = MissingNode.getInstance();
            String rawData = null;

            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    var name = parser.currentName();
                    parser.nextToken();

                    var value = RawJson.readValue(parser, json, "data");
                    if (name.equals("type")) {
                        type = value.tree().asString();
                    } else if (name.equals(payloadKey)) {
                        payload = value.tree();
                        rawData = value.raw();
                    }
                }
            }

            return new Item(type, payload, rawData);
        }
    }
}
