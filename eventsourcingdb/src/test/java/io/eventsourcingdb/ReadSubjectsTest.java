package io.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.eventsourcingdb.testcontainers.Container;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadSubjectsTest {
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

    private List<String> readSubjects(String baseSubject) {
        try (var subjects = client.readSubjects(baseSubject)) {
            return subjects.toList();
        }
    }

    private void writeTwoEvents() {
        client.writeEvents(List.of(
                new EventCandidate("https://www.eventsourcingdb.io", "/test/1", "io.eventsourcingdb.test", Map.of()),
                new EventCandidate("https://www.eventsourcingdb.io", "/test/2", "io.eventsourcingdb.test", Map.of())));
    }

    @Test
    void readsNoSubjectsIfTheDatabaseIsEmpty() {
        assertEquals(List.of(), readSubjects("/"));
    }

    @Test
    void readsAllSubjects() {
        writeTwoEvents();

        assertEquals(List.of("/", "/test", "/test/1", "/test/2"), readSubjects("/"));
    }

    @Test
    void readsAllSubjectsFromTheBaseSubject() {
        writeTwoEvents();

        assertEquals(List.of("/test", "/test/1", "/test/2"), readSubjects("/test"));
    }
}
