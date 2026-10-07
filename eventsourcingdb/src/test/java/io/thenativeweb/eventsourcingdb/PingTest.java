package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PingTest {
    private Container container;

    @BeforeEach
    void startContainer() {
        container = Database.start();
    }

    @AfterEach
    void stopContainer() {
        container.stop();
    }

    @Test
    void doesNotThrowIfTheServerIsReachable() {
        var client = container.getClient();

        assertDoesNotThrow(client::ping);
    }

    @Test
    void throwsIfTheServerIsNotReachable() {
        var client = new Client(FakeServer.unusedUrl(), "secret");

        var exception = assertThrows(EventSourcingDbException.class, client::ping);
        assertEquals("failed to ping", exception.getMessage());
    }

    @Test
    void throwsIfTheServerDoesNotConfirmThePing() {
        try (var server = FakeServer.respondingWith(200, "{\"type\":\"something-else\"}")) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(EventSourcingDbException.class, client::ping);
            assertEquals("failed to ping", exception.getMessage());
        }
    }
}
