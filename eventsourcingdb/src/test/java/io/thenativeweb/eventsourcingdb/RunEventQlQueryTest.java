package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RunEventQlQueryTest {
    private Container container;
    private Client client;

    @BeforeEach
    void startContainer() {
        container = Database.start();
        client = container.getClient();
    }

    @AfterEach
    void stopContainer() {
        container.stop();
    }

    private List<JsonNode> query(String query) {
        try (var rows = client.runEventQlQuery(query)) {
            return rows.toList();
        }
    }

    @Test
    void readsNoRowsIfTheQueryDoesNotReturnAnyRows() {
        assertEquals(List.of(), query("FROM e IN events PROJECT INTO e"));
    }

    @Test
    void readsAllRowsTheQueryReturns() {
        client.writeEvents(List.of(
                new EventCandidate(
                        "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", Map.of("value", 23)),
                new EventCandidate(
                        "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", Map.of("value", 42))));

        var rows = query("FROM e IN events PROJECT INTO e");

        assertEquals(2, rows.size());
        assertEquals("0", rows.get(0).path("id").asString());
        assertEquals(23, rows.get(0).path("data").path("value").asInt());
        assertEquals("1", rows.get(1).path("id").asString());
        assertEquals(42, rows.get(1).path("data").path("value").asInt());
    }
}
