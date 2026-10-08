package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientOptionsTest {
    private static FakeServer pingingServer() {
        return FakeServer.respondingWith(200, "{\"type\":\"io.eventsourcingdb.api.ping-received\"}");
    }

    @Test
    void createsAnHttpClientWithAConnectTimeoutOfTenSeconds() {
        var client = new Client(FakeServer.unusedUrl(), "secret");

        assertEquals(Optional.of(Duration.ofSeconds(10)), client.httpClient().connectTimeout());
    }

    @Test
    void sendsRequestsWithTheGivenHttpClient() {
        try (var server = pingingServer();
                var httpClient = HttpClient.newHttpClient()) {
            var client = new Client(server.url(), "secret", new ClientOptions().withHttpClient(httpClient));

            assertSame(httpClient, client.httpClient());
            assertDoesNotThrow(client::ping);
        }
    }

    @Test
    void failsIfTheGivenHttpClientIsClosed() {
        try (var server = pingingServer()) {
            var httpClient = HttpClient.newHttpClient();
            httpClient.shutdownNow();
            var client = new Client(server.url(), "secret", new ClientOptions().withHttpClient(httpClient));

            var exception = assertThrows(EventSourcingDbException.class, client::ping);

            assertEquals("failed to ping", exception.getMessage());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    @Test
    void createsOptionsWithoutAnHttpClient() {
        assertEquals(new ClientOptions(null), new ClientOptions());
    }
}
