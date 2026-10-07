package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RawJsonTest {
    @Test
    void returnsTheElementsOfAnArrayAsTheyAreWritten() {
        assertEquals(
                List.of("{\"a\":\"\\u003c\"}", "[1, 2]", "1.50", "\"\\u0026\"", "null"),
                RawJson.elements("[{\"a\":\"\\u003c\"}, [1, 2], 1.50, \"\\u0026\", null]"));
    }

    @Test
    void failsIfTheJsonIsNoArray() {
        var exception = assertThrows(EventSourcingDbException.class, () -> RawJson.elements("{}"));

        assertEquals("failed to parse JSON, expected an array", exception.getMessage());
    }

    @Test
    void returnsAPropertyAsItIsWritten() {
        assertEquals(
                "{\"z\":\"\\u0026\"}",
                RawJson.property("{\"x\":[1,{\"data\":2}],\"data\":{\"z\":\"\\u0026\"},\"y\":3}", "data"));
        assertEquals("\"\\u0026\"", RawJson.property("{\"data\":\"\\u0026\"}", "data"));
    }

    @Test
    void failsIfThePropertyIsMissing() {
        var exception = assertThrows(EventSourcingDbException.class, () -> RawJson.property("{\"x\":1}", "data"));

        assertEquals("failed to parse JSON, property 'data' is missing", exception.getMessage());
    }
}
