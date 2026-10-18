package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InvalidServerHeaderExceptionTest {
    static List<Arguments> all() {
        return Operations.onEveryApi(Operations.all());
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsAServerWithoutAServerHeader(ApiVersion api, Operation operation) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> exchange.sendResponseHeaders(200, -1))) {
            var client = new Client(server.url(), "secret");

            var exception = Operations.assertFailsAfterSending(InvalidServerHeaderException.class, operation, client);

            assertEquals("server must be EventSourcingDB", exception.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsAnotherServerInsteadOfItsStatus(ApiVersion api, Operation operation) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", "nginx");
            exchange.sendResponseHeaders(502, -1);
        })) {
            var client = new Client(server.url(), "secret");

            Operations.assertFailsAfterSending(InvalidServerHeaderException.class, operation, client);
        }
    }
}
