package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DbApiExceptionTest {
    static List<Arguments> all() {
        return Operations.onEveryApi(Operations.all());
    }

    static List<Operation> authenticated() {
        return Operations.authenticated();
    }

    private static FakeServer respondingWith(ApiVersion api, int status, String retryAfter) {
        return FakeServer.start(api, (exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
            FakeServer.respond(exchange, status, "too many requests");
        });
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsAnUnexpectedStatusWithItsStatusAndDescription(ApiVersion api, Operation operation) {
        try (var server = FakeServer.respondingWith(api, 500, "  something went wrong\n")) {
            var client = new Client(server.url(), "secret");

            var exception = Operations.assertFailsAfterSending(DbApiException.class, operation, client);

            assertEquals(
                    "failed to " + operation.action()
                            + ", got HTTP status code '500', expected '200': something went wrong",
                    exception.getMessage());
            assertEquals(operation.action(), exception.action());
            assertEquals(500, exception.status());
            assertEquals("", exception.code());
            assertEquals("something went wrong", exception.description());
            assertEquals(Json.MAPPER.createObjectNode(), exception.details());
            assertEquals(Optional.empty(), exception.retryAfter());
        }
    }

    @ParameterizedTest
    @MethodSource("authenticated")
    void everyRequestReportsARejectedTokenWithStatus401(Operation operation) {
        var container = Database.start();
        try {
            var client = new Client(container.getBaseUrl(), "invalid-token");

            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(client));

            assertEquals(401, exception.status());
            assertEquals("", exception.code());
        } finally {
            container.stop();
        }
    }

    @Test
    void leavesOutTheDescriptionIfTheServerGivesNone() {
        try (var server = FakeServer.respondingWith(ApiVersion.V1, 503, "")) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertEquals("failed to ping, got HTTP status code '503', expected '200'", exception.getMessage());
            assertEquals("", exception.description());
        }
    }

    @Test
    void cutsALongDescriptionShort() {
        try (var server = FakeServer.respondingWith(ApiVersion.V1, 500, "x".repeat(10_000))) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertEquals("x".repeat(4096), exception.description());
        }
    }

    @Test
    void reportsIfTheDescriptionCannotBeRead() {
        try (var server = FakeServer.start(ApiVersion.V1, (exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(500, 1000);
            exchange.getResponseBody().write("something".getBytes());
        })) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertTrue(
                    exception
                            .getMessage()
                            .startsWith(
                                    "failed to ping, got HTTP status code '500', expected '200', and failed to read the reason: "));
            assertEquals("", exception.description());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsHowLongToWaitIfThereAreTooManyRequests(ApiVersion api, Operation operation) {
        try (var server = respondingWith(api, 429, "7")) {
            var client = new Client(server.url(), "secret");

            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(client));

            assertEquals(429, exception.status());
            assertEquals("too many requests", exception.description());
            assertEquals(Optional.of(Duration.ofSeconds(7)), exception.retryAfter());
            assertEquals(1, server.requestCount());
        }
    }

    // The server sends a number of seconds only, so anything else, e.g. a
    // date, is left out rather than guessed at.
    @ParameterizedTest
    @ValueSource(strings = {"", "soon", "-1", "1.5", "Wed, 21 Oct 2026 07:28:00 GMT"})
    void leavesOutHowLongToWaitIfItIsNoNumberOfSeconds(String retryAfter) {
        try (var server = respondingWith(ApiVersion.V1, 429, retryAfter)) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertEquals(Optional.empty(), exception.retryAfter());
        }
    }

    @Test
    void leavesOutHowLongToWaitIfThereAreNotTooManyRequests() {
        try (var server = respondingWith(ApiVersion.V1, 503, "7")) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertEquals(Optional.empty(), exception.retryAfter());
        }
    }
}
