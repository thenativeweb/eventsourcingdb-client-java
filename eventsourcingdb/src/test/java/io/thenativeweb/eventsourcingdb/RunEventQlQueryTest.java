package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

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

    record Row(String id, int value) {}

    record BookTitle(String bookTitle) {}

    private void writeTwoEvents() {
        client.writeEvents(List.of(
                new EventCandidate(
                        "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", Map.of("value", 23)),
                new EventCandidate(
                        "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", Map.of("value", 42))));
    }

    private <T> List<T> query(Client client, String query, Class<T> rowType) {
        try (var rows = client.runEventQlQuery(query, rowType)) {
            return rows.toList();
        }
    }

    @Test
    void readsRowsAsTheGivenType() {
        writeTwoEvents();

        var rows = query(client, "FROM e IN events PROJECT INTO { id: e.id, value: e.data.value }", Row.class);

        assertEquals(List.of(new Row("0", 23), new Row("1", 42)), rows);
    }

    @Test
    void readsScalarRowsAsTheGivenType() {
        writeTwoEvents();

        assertEquals(List.of(2), query(client, "FROM e IN events PROJECT INTO COUNT()", Integer.class));
    }

    @Test
    void readsRowsWithTheDataMapperOfTheClient() {
        writeTwoEvents();
        var snakeCaseClient = new Client(
                container.getBaseUrl(),
                container.getApiToken(),
                new ClientOptions()
                        .withDataMapper(JsonMapper.builder()
                                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                                .build()));

        var rows = query(
                snakeCaseClient,
                "FROM e IN events WHERE e.id == \"0\" PROJECT INTO { book_title: e.type }",
                BookTitle.class);

        assertEquals(List.of(new BookTitle("io.eventsourcingdb.test")), rows);
    }

    @Test
    void endsTheStreamIfARowDoesNotMatchTheType() {
        writeTwoEvents();

        assertThrows(JacksonException.class, () -> query(client, "FROM e IN events PROJECT INTO e.id", Row.class));
    }
}
