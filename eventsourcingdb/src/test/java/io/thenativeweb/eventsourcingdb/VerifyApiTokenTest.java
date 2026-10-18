package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        assertEquals(401, exception.status());
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void doesNotThrowIfTheServerConfirmsTheToken(ApiVersion api) {
        var confirmation = api == ApiVersion.V1
                ? "{\"type\":\"io.eventsourcingdb.api.api-token-verified\"}"
                : api.item("event", "{\"type\":\"io.eventsourcingdb.api.token-verified\"}");

        try (var server = FakeServer.respondingWith(api, 200, confirmation)) {
            var client = new Client(server.url(), "secret");

            assertDoesNotThrow(client::verifyApiToken);
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void throwsIfTheServerDoesNotConfirmTheToken(ApiVersion api) {
        try (var server = FakeServer.respondingWith(api, 200, api.item("event", "{\"type\":\"something-else\"}"))) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(EventSourcingDbException.class, client::verifyApiToken);
            assertEquals("failed to verify API token", exception.getMessage());
        }
    }
}
