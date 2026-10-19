package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
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
    static List<Arguments> all() {
        return Operations.onEveryApi(Operations.all());
    }

    static List<Arguments> streams() {
        return Operations.onEveryApi(Operations.streams());
    }

    private record Outcome(Throwable thrown, boolean wasInterrupted) {}

    @FunctionalInterface
    private interface Moment {
        void await() throws InterruptedException;
    }

    // Runs the operation in a thread of its own, interrupts the thread a while
    // after the server got the request, and returns how the operation ended.
    private static Outcome interruptAfterRequest(FakeServer server, Runnable operation) throws InterruptedException {
        return interruptAt(
                () -> {
                    server.awaitRequest();
                    Thread.sleep(Duration.ofMillis(300));
                },
                operation);
    }

    // Runs the operation in a thread of its own, interrupts the thread once
    // the moment has come, and returns how the operation ended.
    private static Outcome interruptAt(Moment moment, Runnable operation) throws InterruptedException {
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

        moment.await();
        thread.interrupt();
        thread.join();

        return new Outcome(thrown.get(), wasInterrupted.get());
    }

    @ParameterizedTest
    @MethodSource("all")
    void anInterruptEndsARequestTheServerDoesNotAnswer(ApiVersion api, Operation operation)
            throws InterruptedException {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> fakeServer.hang())) {
            var client = new Client(server.url(), "secret");

            var outcome = interruptAfterRequest(server, () -> operation.call().accept(client));

            var cancellation =
                    Operations.assertFailedAfterSending(CancellationException.class, operation, outcome.thrown());
            assertEquals("failed to " + operation.action() + ", the request was canceled", cancellation.getMessage());
            assertTrue(outcome.wasInterrupted());
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    void anInterruptedThreadDoesNotSendTheRequest(ApiVersion api, Operation operation) {
        try (var server = FakeServer.respondingWith(api, 200, "")) {
            var client = new Client(server.url(), "secret");

            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> operation.call().accept(client));
            } finally {
                assertTrue(Thread.interrupted());
            }

            assertEquals(List.of(), server.requests());
        }
    }

    @ParameterizedTest
    @MethodSource("streams")
    void aCloseEndsAStreamWhoseRequestTheServerDoesNotAnswer(ApiVersion api, Operation operation) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> fakeServer.hang());
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
    void aStreamThatIsClosedBeforeItStartsDoesNotSendTheRequest(ApiVersion api, Operation operation) {
        try (var server = FakeServer.respondingWith(api, 200, "")) {
            var items = operation.open().apply(new Client(server.url(), "secret"));
            var iterator = items.iterator();

            items.close();

            assertThrows(CancellationException.class, iterator::hasNext);
            assertEquals(List.of(), server.requests());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void anInterruptEndsAWriteWhoseResponseStopsInTheMiddle(ApiVersion api) throws InterruptedException {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> {
            exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("[".getBytes());
            exchange.getResponseBody().flush();
            fakeServer.hang();
        })) {
            var client = new Client(server.url(), "secret");

            var outcome = interruptAfterRequest(server, () -> client.writeEvents(List.of()));

            var outcomeUnknown = assertInstanceOf(OutcomeUnknownException.class, outcome.thrown());
            assertInstanceOf(CancellationException.class, outcomeUnknown.getCause());
            assertTrue(outcome.wasInterrupted());
        }
    }

    private static FakeServer streamingOneSubjectAndHanging(ApiVersion api) {
        return FakeServer.start(api, (exchange, fakeServer) -> {
            FakeServer.startStream(exchange);
            FakeServer.writeLine(exchange, Lines.subject(api));
            fakeServer.hang();
        });
    }

    // The stream is closed once the caller has its first subject, rather than
    // after a fixed time, which a slow machine may need to deliver it.
    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void aCloseEndsAStreamThatStopsInTheMiddle(ApiVersion api) {
        try (var server = streamingOneSubjectAndHanging(api);
                var subjects = new Client(server.url(), "secret").readSubjects("/", new ReadSubjectsOptions(true));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var subjectsRead = new ArrayList<String>();
            var hasSubject = new CountDownLatch(1);
            Runnable close = subjects::close;
            executor.submit(() -> {
                hasSubject.await();
                close.run();
                return null;
            });

            assertThrows(
                    CancellationException.class,
                    () -> subjects.forEach(subject -> {
                        subjectsRead.add(subject);
                        hasSubject.countDown();
                    }));

            assertEquals(List.of("/"), subjectsRead);
        }
    }

    // The thread is interrupted once the caller has its first subject, rather
    // than after a fixed time, which a slow machine may need to deliver it.
    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void anInterruptEndsAStreamThatStopsInTheMiddle(ApiVersion api) throws InterruptedException {
        try (var server = streamingOneSubjectAndHanging(api)) {
            var client = new Client(server.url(), "secret");
            var subjectsRead = new ArrayList<String>();
            var hasSubject = new CountDownLatch(1);

            var outcome = interruptAt(hasSubject::await, () -> {
                try (var subjects = client.readSubjects("/", new ReadSubjectsOptions(true))) {
                    subjects.forEach(subject -> {
                        subjectsRead.add(subject);
                        hasSubject.countDown();
                    });
                }
            });

            assertInstanceOf(CancellationException.class, outcome.thrown());
            assertTrue(outcome.wasInterrupted());
            assertEquals(List.of("/"), subjectsRead);
        }
    }
}
