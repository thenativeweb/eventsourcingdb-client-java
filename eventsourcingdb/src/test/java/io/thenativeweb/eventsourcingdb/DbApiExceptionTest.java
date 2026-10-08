package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DbApiExceptionTest {
    static List<Operation> all() {
        return Operations.all();
    }

    static List<Operation> authenticated() {
        return Operations.authenticated();
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsAnUnexpectedStatusWithItsStatusCodeAndReason(Operation operation) {
        try (var server = FakeServer.respondingWith(500, "  something went wrong\n")) {
            var client = new Client(server.url(), "secret");

            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(client));

            assertEquals(
                    "failed to " + operation.action()
                            + ", got HTTP status code '500', expected '200': something went wrong",
                    exception.getMessage());
            assertEquals(operation.action(), exception.action());
            assertEquals(500, exception.statusCode());
            assertEquals("something went wrong", exception.reason());
        }
    }

    @ParameterizedTest
    @MethodSource("authenticated")
    void everyRequestReportsARejectedTokenWithStatusCode401(Operation operation) {
        var container = Database.start();
        try {
            var client = new Client(container.getBaseUrl(), "invalid-token");

            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(client));

            assertEquals(401, exception.statusCode());
        } finally {
            container.stop();
        }
    }

    @Test
    void leavesOutTheReasonIfTheServerGivesNone() {
        try (var server = FakeServer.respondingWith(503, "")) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertEquals("failed to ping, got HTTP status code '503', expected '200'", exception.getMessage());
            assertEquals("", exception.reason());
        }
    }

    @Test
    void cutsALongReasonShort() {
        try (var server = FakeServer.respondingWith(500, "x".repeat(10_000))) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(DbApiException.class, client::ping);

            assertEquals("x".repeat(4096), exception.reason());
        }
    }

    @Test
    void reportsIfTheReasonCannotBeRead() {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
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
            assertEquals("", exception.reason());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }
}
