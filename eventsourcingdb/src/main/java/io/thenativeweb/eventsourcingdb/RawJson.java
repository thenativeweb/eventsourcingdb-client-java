package io.thenativeweb.eventsourcingdb;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

// RawJson reads JSON text into a tree in a single pass, and keeps the text of
// one property exactly as the server wrote it. Serializing a parsed value again
// may change it, e.g. how characters are escaped, which would break the
// verification of an event's hash. Reading the text only once keeps the cost
// of that close to the cost of parsing it.
final class RawJson {
    private RawJson() {}

    // A value read into a tree, along with the text of the property that was
    // asked for, if the value is an object that has it.
    record Captured(JsonNode tree, @Nullable String raw) {}

    // Reads the value the parser is at, i.e. whose first token it has just
    // read, and leaves the parser at the last token of that value. It reads
    // with the parser rather than with the mapper, since the mapper would
    // fail on the rest of the text that follows the value.
    static Captured readValue(JsonParser parser, String json, String rawPropertyName) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return new Captured(parser.readValueAsTree(), null);
        }

        var tree = Json.MAPPER.createObjectNode();
        String raw = null;

        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            var name = parser.currentName();
            parser.nextToken();

            var start = parser.currentTokenLocation().getCharOffset();
            tree.set(name, (JsonNode) parser.readValueAsTree());

            if (name.equals(rawPropertyName)) {
                var end = parser.currentLocation().getCharOffset();
                raw = json.substring((int) start, (int) end);
            }
        }

        return new Captured(tree, raw);
    }

    static Captured readValue(String json, String rawPropertyName) {
        try (var parser = Json.MAPPER.createParser(json)) {
            parser.nextToken();
            return readValue(parser, json, rawPropertyName);
        }
    }

    static List<Captured> readArray(String json, String rawPropertyName) {
        try (var parser = Json.MAPPER.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new EventSourcingDbException("failed to parse JSON, expected an array");
            }

            var elements = new ArrayList<Captured>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                elements.add(readValue(parser, json, rawPropertyName));
            }

            return elements;
        }
    }
}
