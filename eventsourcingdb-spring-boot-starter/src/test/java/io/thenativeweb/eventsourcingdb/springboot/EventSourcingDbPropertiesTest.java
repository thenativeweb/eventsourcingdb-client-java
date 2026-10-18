package io.thenativeweb.eventsourcingdb.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;

class EventSourcingDbPropertiesTest {
    @Test
    void leavesOutTheStallTimeoutWhenCreatedWithoutIt() {
        var baseUrl = URI.create("http://localhost:3000");

        assertEquals(
                new EventSourcingDbProperties(baseUrl, "secret", null),
                new EventSourcingDbProperties(baseUrl, "secret"));
    }
}
