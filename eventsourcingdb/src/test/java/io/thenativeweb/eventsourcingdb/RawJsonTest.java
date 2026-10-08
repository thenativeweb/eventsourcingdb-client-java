package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawJsonTest {
    @Test
    void readsAnObjectAndKeepsTheTextOfTheGivenProperty() {
        var captured = RawJson.readValue("{\"x\":[1,{\"data\":2}],\"data\":{\"z\":\"\\u0026\"},\"y\":3}", "data");

        assertEquals("{\"z\":\"\\u0026\"}", captured.raw());
        assertEquals("&", captured.tree().path("data").path("z").asString());
        assertEquals(2, captured.tree().path("x").get(1).path("data").asInt());
        assertEquals(3, captured.tree().path("y").asInt());
    }

    @Test
    void keepsTheTextOfAPropertyThatIsNoObject() {
        assertEquals(
                "\"\\u0026\"",
                RawJson.readValue("{\"data\":\"\\u0026\"}", "data").raw());
        assertEquals("1.50", RawJson.readValue("{\"data\":1.50}", "data").raw());
    }

    @Test
    void keepsNoTextIfThePropertyIsMissing() {
        assertNull(RawJson.readValue("{\"x\":1}", "data").raw());
    }

    @Test
    void keepsNoTextIfTheValueIsNoObject() {
        var captured = RawJson.readValue("[1, 2]", "data");

        assertNull(captured.raw());
        assertTrue(captured.tree().isArray());
    }

    @Test
    void readsTheElementsOfAnArray() {
        var elements = RawJson.readArray("[{\"data\":{\"a\":\"\\u003c\"}}, {\"data\":1.50}, 2]", "data");

        assertEquals(
                List.of("{\"a\":\"\\u003c\"}", "1.50"),
                elements.stream().map(RawJson.Captured::raw).limit(2).toList());
        assertEquals(
                Arrays.asList((String) null),
                elements.stream().skip(2).map(RawJson.Captured::raw).toList());
    }

    @Test
    void failsIfTheJsonIsNoArray() {
        var exception = assertThrows(EventSourcingDbException.class, () -> RawJson.readArray("{}", "data"));

        assertEquals("failed to parse JSON, expected an array", exception.getMessage());
    }
}
