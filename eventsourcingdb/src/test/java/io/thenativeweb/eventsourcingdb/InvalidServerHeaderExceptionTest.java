package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class InvalidServerHeaderExceptionTest {
    static List<Operation> all() {
        return Operations.all();
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsAServerWithoutAServerHeader(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> exchange.sendResponseHeaders(200, -1))) {
            var client = new Client(server.url(), "secret");

            var exception = Operations.assertFailsAfterSending(InvalidServerHeaderException.class, operation, client);

            assertEquals("server must be EventSourcingDB", exception.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void everyRequestReportsAnotherServerInsteadOfItsStatus(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", "nginx");
            exchange.sendResponseHeaders(502, -1);
        })) {
            var client = new Client(server.url(), "secret");

            Operations.assertFailsAfterSending(InvalidServerHeaderException.class, operation, client);
        }
    }
}
