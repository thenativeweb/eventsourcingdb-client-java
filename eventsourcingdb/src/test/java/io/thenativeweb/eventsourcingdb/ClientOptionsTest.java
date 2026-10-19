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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ClientOptionsTest {
    private static FakeServer pingingServer() {
        return FakeServer.respondingWith(ApiVersion.V1, 200, "{\"type\":\"io.eventsourcingdb.api.ping-received\"}");
    }

    // The stall timeout bounds connecting, too, so a connect timeout of the
    // HTTP client must not end a request before it.
    @Test
    void createsAnHttpClientWithoutAConnectTimeout() {
        var client = new Client(FakeServer.unusedUrl(), "secret");

        assertEquals(Optional.empty(), client.httpClient().connectTimeout());
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
        assertEquals(new ClientOptions(null, null), new ClientOptions());
    }

    @Test
    void hasAStallTimeoutOfThirtySecondsByDefault() {
        assertEquals(Duration.ofSeconds(30), new ClientOptions().stallTimeout());
        assertEquals(Duration.ofSeconds(30), new ClientOptions(null, null).stallTimeout());
    }

    @Test
    void setsTheStallTimeoutAndKeepsTheOtherOptions() {
        var httpClient = HttpClient.newHttpClient();
        var dataMapper = JsonMapper.builder().build();

        var options = new ClientOptions()
                .withHttpClient(httpClient)
                .withDataMapper(dataMapper)
                .withStallTimeout(Duration.ofSeconds(5));

        assertEquals(new ClientOptions(httpClient, dataMapper, Duration.ofSeconds(5)), options);
        assertEquals(Duration.ofSeconds(5), options.withHttpClient(httpClient).stallTimeout());
        assertEquals(Duration.ofSeconds(5), options.withDataMapper(dataMapper).stallTimeout());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsAStallTimeoutThatIsNotPositive(long seconds) {
        var options = new ClientOptions();

        var exception = assertThrows(
                IllegalArgumentException.class, () -> options.withStallTimeout(Duration.ofSeconds(seconds)));
        assertEquals("stall timeout must be positive", exception.getMessage());
    }
}
