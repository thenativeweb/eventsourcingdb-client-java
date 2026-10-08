package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// Closing a client ends what it is doing at once, as if each of its requests
// and streams had been aborted, and the client can not be used afterwards.
@Timeout(10)
class CloseTest {
    static List<Operation> all() {
        return Operations.all();
    }

    // Runs the operation in a thread of its own, closes the client a while
    // after the server got the request, and returns what the operation threw.
    private static Throwable closeAfterRequest(FakeServer server, Client client, Runnable operation)
            throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();

        var thread = Thread.ofPlatform().start(() -> {
            try {
                operation.run();
            } catch (RuntimeException ex) {
                thrown.set(ex);
            }
        });

        server.awaitRequest();
        Thread.sleep(Duration.ofMillis(300));
        client.close();
        thread.join();

        return thrown.get();
    }

    @ParameterizedTest
    @MethodSource("all")
    void endsARequestTheServerDoesNotAnswer(Operation operation) throws InterruptedException {
        try (var server = FakeServer.start((exchange, fakeServer) -> fakeServer.hang())) {
            var client = new Client(server.url(), "secret");

            var thrown =
                    closeAfterRequest(server, client, () -> operation.call().accept(client));

            assertInstanceOf(CancellationException.class, thrown);
        }
    }

    @Test
    void endsARequestWhoseResponseStopsInTheMiddle() throws InterruptedException {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("[".getBytes());
            exchange.getResponseBody().flush();
            fakeServer.hang();
        })) {
            var client = new Client(server.url(), "secret");

            var thrown = closeAfterRequest(server, client, () -> client.writeEvents(List.of()));

            assertInstanceOf(CancellationException.class, thrown);
        }
    }

    @Test
    void endsAStreamThatIsRunning() throws InterruptedException {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            FakeServer.startStream(exchange);
            FakeServer.writeLine(exchange, Lines.SUBJECT);
            fakeServer.hang();
        })) {
            var client = new Client(server.url(), "secret");
            var subjectsRead = new ArrayList<String>();

            var thrown = closeAfterRequest(server, client, () -> {
                try (var subjects = client.readSubjects("/")) {
                    subjects.forEach(subjectsRead::add);
                }
            });

            assertInstanceOf(CancellationException.class, thrown);
            assertEquals(List.of("/"), subjectsRead);
        }
    }

    @Test
    void endsAStreamThatHasNotStartedYet() {
        try (var server = FakeServer.respondingWith(200, "")) {
            var client = new Client(server.url(), "secret");
            var subjects = client.readSubjects("/");

            client.close();

            assertThrows(CancellationException.class, subjects::toList);
            assertEquals(0, server.requestCount());
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void rejectsEveryRequestOnceItIsClosed(Operation operation) {
        try (var server = FakeServer.respondingWith(200, "")) {
            var client = new Client(server.url(), "secret");
            client.close();

            var exception = assertThrows(
                    IllegalStateException.class, () -> operation.call().accept(client));

            assertEquals("client is closed", exception.getMessage());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void doesNothingWhenItIsClosedAgain() {
        var client = new Client(FakeServer.unusedUrl(), "secret");
        client.close();

        assertDoesNotThrow(client::close);
    }

    @Test
    void closesTheHttpClientItCreated() throws InterruptedException {
        var client = new Client(FakeServer.unusedUrl(), "secret");

        client.close();

        assertTrue(client.httpClient().awaitTermination(Duration.ofSeconds(5)));
    }

    @Test
    void leavesAGivenHttpClientOpen() throws Exception {
        try (var server = FakeServer.respondingWith(200, "ok");
                var httpClient = HttpClient.newHttpClient()) {
            var client = new Client(server.url(), "secret", new ClientOptions().withHttpClient(httpClient));

            client.close();

            assertFalse(httpClient.isTerminated());
            var response =
                    httpClient.send(HttpRequest.newBuilder(server.url()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("ok", response.body());
        }
    }
}
