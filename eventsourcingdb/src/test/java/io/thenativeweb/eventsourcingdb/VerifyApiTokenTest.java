package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerifyApiTokenTest {
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
    void doesNotThrowIfTheTokenIsValid() {
        var client = container.getClient();

        assertDoesNotThrow(client::verifyApiToken);
    }

    @Test
    void throwsIfTheTokenIsInvalid() {
        var client = new Client(container.getBaseUrl(), "invalid-token");

        var exception = assertThrows(DbApiException.class, client::verifyApiToken);
        assertEquals(401, exception.statusCode());
    }

    @Test
    void throwsIfTheServerDoesNotConfirmTheToken() {
        try (var server = FakeServer.respondingWith(200, "{\"type\":\"something-else\"}")) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(EventSourcingDbException.class, client::verifyApiToken);
            assertEquals("failed to verify API token", exception.getMessage());
        }
    }
}
