package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
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

    private List<String> readSubjects(String subject, boolean recursive) {
        try (var subjects = client.readSubjects(subject, new ReadSubjectsOptions(recursive))) {
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
        assertEquals(List.of(), readSubjects("/", true));
    }

    @Test
    void readsAllSubjects() {
        writeTwoEvents();

        assertEquals(List.of("/", "/test", "/test/1", "/test/2"), readSubjects("/", true));
    }

    @Test
    void readsAllSubjectsWithinTheSubject() {
        writeTwoEvents();

        assertEquals(List.of("/test", "/test/1", "/test/2"), readSubjects("/test", true));
    }

    @Test
    void readsTheSubjectAloneIfItIsNotToReadRecursively() {
        writeTwoEvents();

        assertEquals(List.of("/test"), readSubjects("/test", false));
        assertEquals(List.of("/test/1"), readSubjects("/test/1", false));
    }

    // The server names a subject without a slash at its end, e.g. /test for
    // /test/, whichever API the client reads it through.
    @Test
    void readsTheSubjectAloneAsTheServerNamesIt() {
        writeTwoEvents();

        assertEquals(List.of("/test"), readSubjects("/test/", false));
        assertEquals(List.of("/"), readSubjects("/", false));
    }

    @Test
    void readsNoSubjectIfThereAreNoEventsWithinIt() {
        writeTwoEvents();

        assertEquals(List.of(), readSubjects("/other", false));
    }
}
