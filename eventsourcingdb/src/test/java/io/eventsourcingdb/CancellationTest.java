package io.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.eventsourcingdb.Operations.Operation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// Java has no context like Go, so a caller aborts a request by interrupting
// the thread that makes it, and a stream by closing it. Either way, the
// request then ends with a CancellationException, so that an aborted read can
// be told apart from a complete one.
//
// The timeout of this class must not cover starting a container: if it
// interrupts Testcontainers while it looks for Docker, Testcontainers never
// tries again, and every later test that needs a container fails. Tests that
// need EventSourcingDB itself start it in @BeforeEach elsewhere, e.g. in
// WriteEventsTest, which this timeout does not cover.
@Timeout(10)
class CancellationTest {
    static List<Operation> all() {
        return Operations.all();
    }

    static List<Operation> streams() {
        return Operations.streams();
    }

    private record Outcome(Throwable thrown, boolean wasInterrupted) {}

    // Runs the operation in a thread of its own, interrupts the thread a while
    // after the server got the request, and returns how the operation ended.
    private static Outcome interruptAfterRequest(FakeServer server, Runnable operation) throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();
        var wasInterrupted = new AtomicBoolean();

        var thread = Thread.ofPlatform().start(() -> {
            try {
                operation.run();
            } catch (RuntimeException ex) {
                thrown.set(ex);
            }
            wasInterrupted.set(Thread.currentThread().isInterrupted());
        });

        server.awaitRequest();
        Thread.sleep(Duration.ofMillis(300));
        thread.interrupt();
        thread.join();

        return new Outcome(thrown.get(), wasInterrupted.get());
    }

    @ParameterizedTest
    @MethodSource("all")
    void anInterruptEndsARequestTheServerDoesNotAnswer(Operation operation) throws InterruptedException {
        try (var server = FakeServer.start((exchange, fakeServer) -> fakeServer.hang())) {
            var client = new Client(server.url(), "secret");

            var outcome = interruptAfterRequest(server, () -> operation.call().accept(client));

            assertInstanceOf(CancellationException.class, outcome.thrown());
            assertEquals(
                    "failed to " + operation.action() + ", the request was canceled",
                    outcome.thrown().getMessage());
            assertTrue(outcome.wasInterrupted());
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void anInterruptedThreadDoesNotSendTheRequest(Operation operation) {
        try (var server = FakeServer.respondingWith(200, "")) {
            var client = new Client(server.url(), "secret");

            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> operation.call().accept(client));
            } finally {
                assertTrue(Thread.interrupted());
            }

            assertEquals(0, server.requestCount());
        }
    }

    @ParameterizedTest
    @MethodSource("streams")
    void aCloseEndsAStreamWhoseRequestTheServerDoesNotAnswer(Operation operation) {
        try (var server = FakeServer.start((exchange, fakeServer) -> fakeServer.hang());
                var items = operation.open().apply(new Client(server.url(), "secret"));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Runnable close = items::close;
            executor.submit(() -> {
                server.awaitRequest();
                Thread.sleep(Duration.ofMillis(300));
                close.run();
                return null;
            });

            assertThrows(CancellationException.class, () -> items.forEach(item -> {}));
        }
    }

    @ParameterizedTest
    @MethodSource("streams")
    void aStreamThatIsClosedBeforeItStartsDoesNotSendTheRequest(Operation operation) {
        try (var server = FakeServer.respondingWith(200, "")) {
            var items = operation.open().apply(new Client(server.url(), "secret"));
            var iterator = items.iterator();

            items.close();

            assertThrows(CancellationException.class, iterator::hasNext);
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void anInterruptEndsAWriteWhoseResponseStopsInTheMiddle() throws InterruptedException {
        try (var server = FakeServer.start((exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("[".getBytes());
            exchange.getResponseBody().flush();
            fakeServer.hang();
        })) {
            var client = new Client(server.url(), "secret");

            var outcome = interruptAfterRequest(server, () -> client.writeEvents(List.of()));

            assertInstanceOf(CancellationException.class, outcome.thrown());
            assertTrue(outcome.wasInterrupted());
        }
    }

    private static FakeServer streamingOneSubjectAndHanging() {
        return FakeServer.start((exchange, fakeServer) -> {
            FakeServer.startStream(exchange);
            FakeServer.writeLine(exchange, Lines.SUBJECT);
            fakeServer.hang();
        });
    }

    @Test
    void aCloseEndsAStreamThatStopsInTheMiddle() {
        try (var server = streamingOneSubjectAndHanging();
                var subjects = new Client(server.url(), "secret").readSubjects("/");
                var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var subjectsRead = new ArrayList<String>();
            scheduler.schedule(subjects::close, 300, TimeUnit.MILLISECONDS);

            assertThrows(CancellationException.class, () -> subjects.forEach(subjectsRead::add));

            assertEquals(List.of("/"), subjectsRead);
        }
    }

    @Test
    void anInterruptEndsAStreamThatStopsInTheMiddle() throws InterruptedException {
        try (var server = streamingOneSubjectAndHanging()) {
            var client = new Client(server.url(), "secret");
            var subjectsRead = new ArrayList<String>();

            var outcome = interruptAfterRequest(server, () -> {
                try (var subjects = client.readSubjects("/")) {
                    subjects.forEach(subjectsRead::add);
                }
            });

            assertInstanceOf(CancellationException.class, outcome.thrown());
            assertTrue(outcome.wasInterrupted());
            assertEquals(List.of("/"), subjectsRead);
        }
    }
}
