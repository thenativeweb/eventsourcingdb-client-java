package io.thenativeweb.eventsourcingdb;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

// RawJson finds values in JSON text and returns them exactly as the server
// wrote them. Serializing a parsed value again may change it, e.g. how
// characters are escaped, which would break the verification of an event's
// hash.
final class RawJson {
    private RawJson() {}

    static List<String> elements(String array) {
        try (var parser = Json.MAPPER.createParser(array)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new EventSourcingDbException("failed to parse JSON, expected an array");
            }

            var elements = new ArrayList<String>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                elements.add(raw(array, parser));
            }

            return elements;
        }
    }

    static String property(String object, String name) {
        try (var parser = Json.MAPPER.createParser(object)) {
            parser.nextToken();

            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                var currentName = parser.currentName();
                parser.nextToken();

                if (currentName.equals(name)) {
                    return raw(object, parser);
                }

                parser.skipChildren();
            }

            throw new EventSourcingDbException("failed to parse JSON, property '" + name + "' is missing");
        }
    }

    private static String raw(String json, JsonParser parser) {
        var start = parser.currentTokenLocation().getCharOffset();

        parser.skipChildren();
        parser.finishToken();

        var end = parser.currentLocation().getCharOffset();

        return json.substring((int) start, (int) end);
    }
}
