package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class DataMapperTest {
    record BookAcquired(String bookTitle, String authorName) {}

    private static final JsonMapper SNAKE_CASE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private Container container;

    @BeforeEach
    void startContainer() {
        container = Database.start();
    }

    @AfterEach
    void stopContainer() {
        container.stop();
    }

    private Client clientWith(JsonMapper dataMapper) {
        return new Client(
                container.getBaseUrl(), container.getApiToken(), new ClientOptions().withDataMapper(dataMapper));
    }

    private static EventCandidate candidate(Object data) {
        return new EventCandidate(
                "https://library.eventsourcingdb.io", "/books/42", "io.eventsourcingdb.library.book-acquired", data);
    }

    @Test
    void serializesTheDataOfEventsWithTheGivenDataMapper() {
        var client = clientWith(SNAKE_CASE);

        var event = client.writeEvents(List.of(candidate(new BookAcquired("2001", "Arthur C. Clarke"))))
                .getFirst();

        assertEquals("2001", event.data().path("book_title").asString());
        assertEquals("Arthur C. Clarke", event.data().path("author_name").asString());
    }

    @Test
    void deserializesTheDataOfEventsWithTheGivenDataMapper() {
        var client = clientWith(SNAKE_CASE);
        client.writeEvents(List.of(candidate(Map.of("book_title", "2001", "author_name", "Arthur C. Clarke"))));

        try (var events = client.readEvents("/books/42", new ReadEventsOptions(false))) {
            var event = events.toList().getFirst();

            assertEquals(new BookAcquired("2001", "Arthur C. Clarke"), event.data(BookAcquired.class));
        }
    }

    @Test
    void readsWhatTheServerSendsWithAJsonMapperOfItsOwn() {
        // This configuration would break reading the fields of an event, such
        // as predecessorhash, if the client used it for anything but data.
        var strictSnakeCase = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        var client = clientWith(strictSnakeCase);

        var writtenEvent = client.writeEvents(List.of(candidate(new BookAcquired("2001", "Arthur C. Clarke"))))
                .getFirst();

        try (var events = client.readEvents("/books/42", new ReadEventsOptions(false))) {
            var event = events.toList().getFirst();

            assertEquals(writtenEvent.id(), event.id());
            assertEquals("0".repeat(64), event.predecessorHash());
            event.verifyHash();
        }
    }
}
