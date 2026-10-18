package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// A request fails once nothing arrives from the server for the stall timeout,
// however far it got, but it is never cut off while something keeps arriving.
@Timeout(10)
class ConnectionStalledExceptionTest {
    private static final Duration STALL_TIMEOUT = Duration.ofMillis(300);

    // The streams of these operations carry heartbeats, so a stall within them
    // ends with a HeartbeatTimeoutException instead.
    private static final Set<String> HEARTBEAT_STREAMS = Set.of("observe events", "run EventQL query");

    static List<Operation> all() {
        return Operations.all();
    }

    static List<Operation> requests() {
        return Operations.all().stream()
                .filter(operation -> !operation.isStream())
                .toList();
    }

    static List<Operation> streamsWithoutHeartbeats() {
        return Operations.streams().stream()
                .filter(operation -> !HEARTBEAT_STREAMS.contains(operation.action()))
                .toList();
    }

    private static Client clientWith(FakeServer server) {
        return new Client(server.url(), "secret", new ClientOptions().withStallTimeout(STALL_TIMEOUT));
    }

    // Checks the type and the message of the stall, and that its cause is of
    // the given type, so that callers who check the cause can rely on it.
    private static void assertStalled(Operation operation, Client client, Class<? extends Throwable> causeType) {
        var exception = assertThrows(
                ConnectionStalledException.class, () -> operation.call().accept(client));

        assertEquals(
                "failed to " + operation.action() + ", nothing arrived from the server for 300 milliseconds",
                exception.getMessage());
        assertInstanceOf(causeType, exception.getCause());
    }

    // Writes the text in pieces with a pause after each, so that the whole
    // text takes longer than the stall timeout, but no single pause does.
    private static void writeInPieces(OutputStream body, String text) throws IOException, InterruptedException {
        var bytes = text.getBytes(UTF_8);
        var pieceLength = 4;

        for (var offset = 0; offset < bytes.length; offset += pieceLength) {
            body.write(bytes, offset, Math.min(pieceLength, bytes.length - offset));
            body.flush();
            Thread.sleep(STALL_TIMEOUT.dividedBy(3));
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void endsARequestWhoseResponseDoesNotArrive(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> fakeServer.hang())) {
            assertStalled(operation, clientWith(server), HttpTimeoutException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("requests")
    void endsARequestWhoseResponseStopsInTheMiddle(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("[".getBytes(UTF_8));
            exchange.getResponseBody().flush();
            fakeServer.hang();
        })) {
            assertStalled(operation, clientWith(server), IOException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("streamsWithoutHeartbeats")
    void endsAStreamWithoutHeartbeatsThatStopsInTheMiddle(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            FakeServer.startStream(exchange);
            FakeServer.writeLine(exchange, Lines.HEARTBEAT);
            fakeServer.hang();
        })) {
            assertStalled(operation, clientWith(server), IOException.class);
        }
    }

    @Test
    void endsAnErrorWhoseReasonStopsInTheMiddle() {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(500, 1000);
            exchange.getResponseBody().write("something".getBytes(UTF_8));
            exchange.getResponseBody().flush();
            fakeServer.hang();
        })) {
            assertStalled(Operation.request("ping", Client::ping), clientWith(server), IOException.class);
        }
    }

    @Test
    void keepsARequestAliveWhileItsResponseArrives() {
        var response = "{\"type\":\"io.eventsourcingdb.api.ping-received\"}";

        try (var server = FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(200, response.getBytes(UTF_8).length);
            writeInPieces(exchange.getResponseBody(), response);
        })) {
            assertDoesNotThrow(clientWith(server)::ping);
        }
    }

    @Test
    void keepsAStreamAliveWhileALineArrives() {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    writeInPieces(exchange.getResponseBody(), Lines.SUBJECT + "\n");
                });
                var subjects = clientWith(server).readSubjects("/")) {
            assertEquals(List.of("/"), subjects.toList());
        }
    }

    // The second subject arrives while the caller is still busy with the
    // first, so the client has to read again after the caller's time.
    @Test
    void doesNotCountTheTimeTheCallerSpendsOnAnItem() {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, Lines.SUBJECT);
                    Thread.sleep(STALL_TIMEOUT.dividedBy(3));
                    FakeServer.writeLine(exchange, Lines.SUBJECT);
                    fakeServer.hang();
                });
                var subjects = clientWith(server).readSubjects("/")) {
            var count = subjects.limit(2)
                    .peek(subject -> sleep(STALL_TIMEOUT.multipliedBy(2)))
                    .count();

            assertEquals(2, count);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
