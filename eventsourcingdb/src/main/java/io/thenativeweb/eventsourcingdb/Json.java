package io.thenativeweb.eventsourcingdb;

import java.util.Locale;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}

    // Turns a constant such as READ_EVERYTHING into the value the server
    // expects, such as read-everything.
    static String value(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
